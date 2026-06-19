package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.DvmField
import com.vortexdbg.linux.android.dvm.DvmMethod
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.mcp.McpTools

/**
 * [DvmSubTools] group for read-only reflective inspection of the DVM's lazily-built model: the native
 * RegisterNatives bindings, the touched members of a class, and decoding of a method descriptor.
 *
 * IMPORTANT lazy-model caveat: Vortex builds its DvmClass member maps on demand (only what the running
 * `.so` has looked up so far via GetMethodID/GetFieldID/RegisterNatives). So these tools report what the
 * VM has *touched*, not the full Java class definition. None of these tools mutate VM state; in
 * particular dvm_describe_method does NOT call getMethodID/getStaticMethodID (those register the member
 * as a side effect) — it only searches what is already registered and otherwise pure-parses the input.
 *
 * Tools (with an example natural-language prompt for an AI):
 *  - `dvm_list_native_registrations`: dump every RegisterNatives binding (signature -> pointer), optionally per class.
 *    Example prompt: "List all native methods registered so far for com.example.Foo."
 *  - `dvm_describe_class`: report the touched members (methods/fields) and natives of a resolved class.
 *    Example prompt: "Describe what the VM knows about class com/example/Foo."
 *  - `dvm_describe_method`: decode a method descriptor and report its static/native-bound status.
 *    Example prompt: "Decode the signature seal(Ljava/lang/String;)Ljava/lang/String; on Foo — is it native?"
 */
class DvmReflectTools(private val emulator: Emulator<*>, private val vm: VM) : DvmSubTools {

    private val names = setOf(
            "dvm_list_native_registrations",
            "dvm_describe_class",
            "dvm_describe_method")

    override fun handles(name: String): Boolean = names.contains(name)

    override fun schemas(): JSONArray {
        val tools = JSONArray()
        tools.add(DvmSupport.schema("dvm_list_native_registrations",
                "[DVM/Java] List every RegisterNatives binding the VM currently holds: for each resolved class, dump its " +
                        "nativesMap entries (method JNI signature -> native function pointer, peer shown as 0x-hex). Optional " +
                        "'class' filter (dot or slash form) restricts output to one class. This reflects only classes/methods " +
                        "the running .so has registered so far (lazy model).",
                DvmSupport.param("class", "Optional. Class name (dot or slash form) to filter, e.g. com.x.Foo or com/x/Foo.")))
        tools.add(DvmSupport.schema("dvm_describe_class",
                "[DVM/Java] Describe a resolved DVM class: name, superclass, interfaces, and the methods/fields the VM has " +
                        "touched so far (registeredStaticMethods/registeredMethods and registeredStaticFields/registeredFields, " +
                        "each with its JNI signature and static flag). NOTE: this is the lazy model — only members the running " +
                        ".so has looked up (GetMethodID/GetFieldID/RegisterNatives) appear here, not the full Java definition.",
                DvmSupport.param("class", "Class name in dot or slash form, e.g. com.x.Foo or com/x/Foo.")))
        tools.add(DvmSupport.schema("dvm_describe_method",
                "[DVM/Java] Decode a method descriptor for a class. 'method' may be a full JNI signature " +
                        "(e.g. seal(Ljava/lang/String;)Ljava/lang/String;) or a bare method name. When a full signature is given " +
                        "the arg types and return type are decoded; for a bare name they are reported only if the method is already " +
                        "registered. Also reports whether the method is static (by searching registeredMethods/registeredStaticMethods " +
                        "by name) and whether it is native-bound (present in the class nativesMap). Read-only: it does NOT call " +
                        "getMethodID/getStaticMethodID (which would register the method); when nothing is registered it falls back to a " +
                        "pure parse of the descriptor.",
                DvmSupport.param("class", "Class name in dot or slash form, e.g. com.x.Foo or com/x/Foo."),
                DvmSupport.param("method", "Full JNI signature (name+(args)ret) or a bare method name.")))
        return tools
    }

    override fun call(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "dvm_list_native_registrations" -> listNativeRegistrations(args)
                "dvm_describe_class" -> describeClass(args)
                "dvm_describe_method" -> describeMethod(args)
                else -> McpTools.errorResult("Unknown DVM reflect tool: $name")
            }
        } catch (e: Exception) {
            McpTools.errorResult("DVM reflect tool '$name' failed: " + (e.message ?: e.javaClass.name))
        }
    }

    // ---------- tool 1: dvm_list_native_registrations ----------

    private fun listNativeRegistrations(args: JSONObject): JSONObject {
        val base = DvmSupport.baseVm(vm)
        val rawFilter = args.getString("class")
        val filter = if (rawFilter != null && rawFilter.trim().isNotEmpty()) toSlash(rawFilter) else null

        val sb = StringBuilder()
        sb.append("RegisterNatives bindings (lazy: only classes the .so has registered)\n")
        var classCount = 0
        var bindingCount = 0
        var matched = false
        for (c in base.classMap.values) {
            if (filter != null && c.getClassName() != filter) continue
            matched = true
            val natives = c.nativesMap
            if (natives.isEmpty()) {
                // Only print empty classes when a specific filter targeted them.
                if (filter != null) {
                    sb.append(c.getClassName()).append(": (no RegisterNatives bindings)\n")
                }
                continue
            }
            classCount++
            sb.append(c.getClassName()).append(" (").append(natives.size).append("):\n")
            for ((msig, ptr) in natives) {
                bindingCount++
                sb.append("  ").append(msig).append(" -> 0x").append(java.lang.Long.toHexString(ptr.peer)).append('\n')
            }
        }
        if (filter != null && !matched) {
            return McpTools.errorResult("No resolved DVM class named '" + filter + "' (it may not be resolved yet).")
        }
        if (classCount == 0 && filter == null) {
            sb.append("(no class has any RegisterNatives binding yet)\n")
        } else {
            sb.append("totals: ").append(classCount).append(" class(es), ").append(bindingCount).append(" binding(s)\n")
        }
        return McpTools.textResult(sb.toString())
    }

    // ---------- tool 2: dvm_describe_class ----------

    private fun describeClass(args: JSONObject): JSONObject {
        val raw = args.getString("class")
        if (raw == null || raw.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'class'.")
        }
        val base = DvmSupport.baseVm(vm)
        val cls = base.findClass(toSlash(raw))
                ?: return McpTools.errorResult("No resolved DVM class named '" + toSlash(raw) +
                        "' (only classes the running .so has touched are resolved).")

        val sb = StringBuilder()
        sb.append("class ").append(cls.getName()).append('\n')
        sb.append("  internal name = ").append(cls.getClassName()).append('\n')
        val sup = cls.getSuperclass()
        sb.append("  superclass = ").append(if (sup != null) sup.getName() else "(none / unresolved)").append('\n')
        val ifaces = cls.getInterfaces()
        if (ifaces != null && ifaces.isNotEmpty()) {
            sb.append("  interfaces (").append(ifaces.size).append("):\n")
            for (it in ifaces) sb.append("    ").append(it.getName()).append('\n')
        } else {
            sb.append("  interfaces = (none recorded)\n")
        }
        sb.append("note: members below are only those the VM has touched so far (lazy model).\n")

        appendMethods(sb, "static methods", cls.registeredStaticMethods())
        appendMethods(sb, "instance methods", cls.registeredMethods())
        appendFields(sb, "static fields", cls.registeredStaticFields())
        appendFields(sb, "instance fields", cls.registeredFields())

        val natives = cls.nativesMap
        sb.append("native-bound (RegisterNatives) (").append(natives.size).append("):\n")
        if (natives.isEmpty()) {
            sb.append("  (none)\n")
        } else {
            for ((msig, ptr) in natives) {
                sb.append("  ").append(msig).append(" -> 0x").append(java.lang.Long.toHexString(ptr.peer)).append('\n')
            }
        }
        return McpTools.textResult(sb.toString())
    }

    private fun appendMethods(sb: StringBuilder, label: String, methods: Collection<DvmMethod>) {
        sb.append(label).append(" (").append(methods.size).append("):\n")
        if (methods.isEmpty()) {
            sb.append("  (none registered)\n")
            return
        }
        for (m in methods) {
            sb.append("  ").append(m.getSignature())
                    .append("  [static=").append(m.isStatic()).append(']')
            if (m.isConstructor()) sb.append(" [ctor]")
            sb.append('\n')
        }
    }

    private fun appendFields(sb: StringBuilder, label: String, fields: Collection<DvmField>) {
        sb.append(label).append(" (").append(fields.size).append("):\n")
        if (fields.isEmpty()) {
            sb.append("  (none registered)\n")
            return
        }
        for (f in fields) {
            sb.append("  ").append(f.getSignature())
                    .append("  [static=").append(f.isStatic()).append("]\n")
        }
    }

    // ---------- tool 3: dvm_describe_method ----------

    private fun describeMethod(args: JSONObject): JSONObject {
        val rawClass = args.getString("class")
        if (rawClass == null || rawClass.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'class'.")
        }
        val rawMethod = args.getString("method")
        if (rawMethod == null || rawMethod.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'method'.")
        }
        val base = DvmSupport.baseVm(vm)
        val className = toSlash(rawClass)
        val cls = base.findClass(className)

        val method = rawMethod.trim()
        val open = method.indexOf('(')
        val close = method.indexOf(')')
        val hasFullSig = open >= 0 && close > open
        val methodName = if (open >= 0) method.substring(0, open) else method

        val sb = StringBuilder()
        sb.append("method ").append(className).append("->").append(methodName).append('\n')
        if (cls == null) {
            sb.append("note: class is not resolved in the VM yet; reporting pure-parse only.\n")
        }

        // Decoded arg/return types when a full signature is supplied.
        if (hasFullSig) {
            val argDescriptor = method.substring(open + 1, close)
            val retDescriptor = method.substring(close + 1)
            val argTypes = try {
                DvmSupport.parseArgTypes(argDescriptor)
            } catch (e: Exception) {
                null
            }
            sb.append("source = full JNI signature\n")
            sb.append("  signature = ").append(method).append('\n')
            if (argTypes == null) {
                sb.append("  args = (could not parse '").append(argDescriptor).append("')\n")
            } else {
                sb.append("  args (").append(argTypes.size).append("):\n")
                if (argTypes.isEmpty()) sb.append("    (none)\n")
                for (t in argTypes) {
                    sb.append("    ").append(t).append("  -> ").append(decodeType(t)).append('\n')
                }
            }
            if (retDescriptor.isEmpty()) {
                sb.append("  return = (missing in signature)\n")
            } else {
                sb.append("  return = ").append(retDescriptor).append("  -> ").append(decodeType(retDescriptor)).append('\n')
            }
        } else {
            sb.append("source = bare method name (no descriptor to decode args/return from)\n")
        }

        // Registered-method search by name (does NOT trigger getMethodID side effects).
        var staticState = "unknown (not registered yet; not querying getMethodID to avoid side effects)"
        val registered = ArrayList<DvmMethod>()
        if (cls != null) {
            for (m in cls.registeredStaticMethods()) if (m.methodName == methodName) registered.add(m)
            for (m in cls.registeredMethods()) if (m.methodName == methodName) registered.add(m)
            if (registered.isNotEmpty()) {
                // Prefer an exact-signature match for the static flag if a full sig was given.
                val target = className + "->" + method
                var exact: DvmMethod? = null
                if (hasFullSig) {
                    for (m in registered) if (m.getSignature() == target) { exact = m; break }
                }
                val pick = exact ?: registered[0]
                staticState = pick.isStatic().toString() +
                        (if (exact == null && registered.size > 1) " (from first of " + registered.size + " registered overloads)" else "")
            }
        }
        sb.append("static = ").append(staticState).append('\n')

        if (registered.isNotEmpty()) {
            sb.append("registered overloads matching name (").append(registered.size).append("):\n")
            for (m in registered) {
                sb.append("  ").append(m.getSignature()).append("  [static=").append(m.isStatic()).append("]\n")
            }
        } else if (cls != null) {
            sb.append("registered overloads matching name: (none touched yet)\n")
        }

        // Native-bound check via nativesMap (keys are method JNI signatures: name+(args)ret).
        if (cls != null) {
            val natives = cls.nativesMap
            val nativeMatches = ArrayList<String>()
            for (msig in natives.keys) {
                val nOpen = msig.indexOf('(')
                val nName = if (nOpen >= 0) msig.substring(0, nOpen) else msig
                if (nName == methodName) {
                    if (hasFullSig) {
                        // With a full sig, prefer the exact entry but still surface name matches.
                        nativeMatches.add(msig)
                    } else {
                        nativeMatches.add(msig)
                    }
                }
            }
            if (nativeMatches.isEmpty()) {
                sb.append("native-bound = false (no nativesMap entry for this name; may bind lazily by Java_ symbol)\n")
            } else {
                sb.append("native-bound = true (").append(nativeMatches.size).append(" RegisterNatives entr(y/ies)):\n")
                for (msig in nativeMatches) {
                    val ptr = natives[msig]
                    sb.append("  ").append(msig)
                    if (ptr != null) sb.append(" -> 0x").append(java.lang.Long.toHexString(ptr.peer))
                    sb.append('\n')
                }
            }
        } else {
            sb.append("native-bound = unknown (class unresolved)\n")
        }

        return McpTools.textResult(sb.toString())
    }

    // ---------- private helpers ----------

    /** Normalize a class name to the slash/internal form used by the DVM maps. */
    private fun toSlash(name: String): String = name.trim().replace('.', '/')

    /** Decode a single JNI type descriptor into a human-readable Java type name. */
    private fun decodeType(descriptor: String): String {
        if (descriptor.isEmpty()) return "?"
        var dims = 0
        var i = 0
        while (i < descriptor.length && descriptor[i] == '[') {
            dims++
            i++
        }
        val base = if (i < descriptor.length) descriptor.substring(i) else ""
        val name = when {
            base == "V" -> "void"
            base == "Z" -> "boolean"
            base == "B" -> "byte"
            base == "C" -> "char"
            base == "S" -> "short"
            base == "I" -> "int"
            base == "J" -> "long"
            base == "F" -> "float"
            base == "D" -> "double"
            base.startsWith("L") && base.endsWith(";") -> base.substring(1, base.length - 1).replace('/', '.')
            else -> base
        }
        val sb = StringBuilder(name)
        for (d in 0 until dims) sb.append("[]")
        return sb.toString()
    }
}
