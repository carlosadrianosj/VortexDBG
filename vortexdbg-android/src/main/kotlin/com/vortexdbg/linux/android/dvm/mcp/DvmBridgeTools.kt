package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.linux.android.dvm.BaseVM
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmField
import com.vortexdbg.linux.android.dvm.DvmMethod
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.mcp.McpTools

/**
 * [DvmSubTools] group bridging the native (emulated ARM) world and the host-JVM DVM world: it maps
 * the opaque 32-bit JNI peers a `.so` sees (jobject / jmethodID / jfieldID / jclass) to the live
 * DVM objects, methods, fields and classes that Vortex holds, and reads JNI call arguments straight
 * out of the CPU registers at a breakpoint.
 *
 * Tools (with an example natural-language prompt for an AI):
 *  - `dvm_resolve_native_handle`: turn a raw jobject/jmethodID/jfieldID peer into the live DVM entity.
 *    Example prompt: "What does the native handle 0xdeadbeef refer to?"
 *  - `dvm_handle_to_native`: inverse — show the native peer + ref scope/refCount for a DVM hash.
 *    Example prompt: "What native pointer does the .so see for DVM handle 0x1234, and is it a global ref?"
 *  - `dvm_args_at_breakpoint`: decode the JNI argument registers at the current stop into DVM objects.
 *    Example prompt: "Decode the JNI arguments for this native method, signature (Ljava/lang/String;I)V."
 *  - `dvm_class_of_native`: identify the class behind a jclass peer or a Java_ mangled export symbol.
 *    Example prompt: "Which class owns the native symbol Java_com_x_Foo_bar and what natives are registered on it?"
 */
class DvmBridgeTools(private val emulator: Emulator<*>, private val vm: VM) : DvmSubTools {

    private val names = setOf(
            "dvm_resolve_native_handle",
            "dvm_handle_to_native",
            "dvm_args_at_breakpoint",
            "dvm_class_of_native")

    override fun handles(name: String): Boolean = names.contains(name)

    override fun schemas(): JSONArray {
        val tools = JSONArray()
        tools.add(DvmSupport.schema("dvm_resolve_native_handle",
                "[DVM/Java] Resolve an opaque native JNI handle (a 32-bit jobject/jmethodID/jfieldID peer that the " +
                        "emulated .so passes across the JNI boundary) to the live DVM entity Vortex holds. The peer is " +
                        "truncated to the Int hash used in the DVM maps. 'kind' selects object|method|field, or 'auto' " +
                        "(default) tries object, then method, then field.",
                DvmSupport.param("value", "The native peer: decimal or 0x-hex 32-bit value."),
                DvmSupport.param("kind", "Optional: object|method|field|auto (default auto).")))
        tools.add(DvmSupport.schema("dvm_handle_to_native",
                "[DVM/Java] Inverse of resolve: given a DVM hash handle, show the native peer the .so observes " +
                        "(0x-hex and the zero-extended 64-bit jobject form) plus its reference scope (local/global/weak) " +
                        "and refCount, by probing the three DVM object maps.",
                DvmSupport.param("hash", "Decimal or 0x-hex JNI hash handle of the object.")))
        tools.add(DvmSupport.schema("dvm_args_at_breakpoint",
                "[DVM/Java] Read the incoming JNI call arguments from the CPU registers at the current breakpoint and " +
                        "try to resolve each slot to a live DVM object (class + value preview) alongside its raw hex. " +
                        "Requires the emulator stopped. JNI calling convention: arg0 = JNIEnv*, arg1 = this (instance " +
                        "methods) or jclass (static methods), then the declared Java arguments follow. If 'signature' " +
                        "is given (a JNI arg descriptor), the declared slots are labelled with their types.",
                DvmSupport.param("count", "Optional. Number of argument slots to dump (default 8).", "integer"),
                DvmSupport.param("signature", "Optional. JNI signature or '(...)' arg descriptor to type the declared " +
                        "slots, e.g. (Ljava/lang/String;I)V.")))
        tools.add(DvmSupport.schema("dvm_class_of_native",
                "[DVM/Java] Identify the DVM class behind a native binding. Pass 'value' (a jclass peer) to get the " +
                        "class name and its registered native functions (RegisterNatives entries: signature -> pointer). " +
                        "Or pass 'symbol' (a Java_ mangled export name, e.g. Java_com_x_Foo_bar) to find the owning class " +
                        "and binding via a best-effort match against each class's nativesMap.",
                DvmSupport.param("value", "Optional. jclass native peer: decimal or 0x-hex."),
                DvmSupport.param("symbol", "Optional. A Java_ mangled native symbol name to match.")))
        return tools
    }

    override fun call(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "dvm_resolve_native_handle" -> resolveNativeHandle(args)
                "dvm_handle_to_native" -> handleToNative(args)
                "dvm_args_at_breakpoint" -> argsAtBreakpoint(args)
                "dvm_class_of_native" -> classOfNative(args)
                else -> McpTools.errorResult("Unknown DVM bridge tool: $name")
            }
        } catch (e: Exception) {
            McpTools.errorResult("DVM bridge tool '$name' failed: " + (e.message ?: e.javaClass.name))
        }
    }

    // ---------- tool 1: dvm_resolve_native_handle ----------

    private fun resolveNativeHandle(args: JSONObject): JSONObject {
        val raw = args.getString("value")
                ?: return McpTools.errorResult("Missing required parameter 'value' (native peer).")
        val hash = parseHandle(raw)
                ?: return McpTools.errorResult("Could not parse 'value' as a peer/hash: $raw")
        val kind = (args.getString("kind") ?: "auto").trim().toLowerCase()
        val base = DvmSupport.baseVm(vm)

        val sb = StringBuilder()
        sb.append("native handle 0x").append(Integer.toHexString(hash))
                .append(" (").append(hash).append(")\n")

        when (kind) {
            "object" -> sb.append(renderObject(base, hash) ?: "  no live DVM object for this handle\n")
            "method" -> sb.append(renderMethod(base, hash) ?: "  no DVM method matches this handle\n")
            "field" -> sb.append(renderField(base, hash) ?: "  no DVM field matches this handle\n")
            "auto" -> {
                val obj = renderObject(base, hash)
                if (obj != null) {
                    sb.append("[object]\n").append(obj)
                } else {
                    val m = renderMethod(base, hash)
                    if (m != null) {
                        sb.append("[method]\n").append(m)
                    } else {
                        val f = renderField(base, hash)
                        if (f != null) {
                            sb.append("[field]\n").append(f)
                        } else {
                            sb.append("  unresolved: no object, method or field matches this handle\n")
                        }
                    }
                }
            }
            else -> return McpTools.errorResult("Invalid 'kind': $kind (expected object|method|field|auto).")
        }
        return McpTools.textResult(sb.toString())
    }

    private fun renderObject(base: BaseVM, hash: Int): String? {
        val obj: DvmObject<*>? = try {
            base.getObject<DvmObject<*>>(hash)
        } catch (e: Exception) {
            null
        }
        if (obj == null) return null
        val sb = StringBuilder()
        sb.append("  class = ").append(DvmSupport.classNameOf(obj)).append('\n')
        val preview = DvmSupport.valuePreview(obj)
        if (preview != null) sb.append("  value = ").append(preview).append('\n')
        sb.append("  ").append(scopeAndRef(base, hash)).append('\n')
        return sb.toString()
    }

    private fun renderMethod(base: BaseVM, hash: Int): String? {
        for (c in base.classMap.values) {
            val m: DvmMethod? = try {
                c.getMethod(hash) ?: c.getStaticMethod(hash)
            } catch (e: Exception) {
                null
            }
            if (m != null) {
                val sb = StringBuilder()
                sb.append("  signature = ").append(m.getSignature()).append('\n')
                sb.append("  static = ").append(m.isStatic()).append('\n')
                sb.append("  declaring = ").append(c.getClassName()).append('\n')
                return sb.toString()
            }
        }
        return null
    }

    private fun renderField(base: BaseVM, hash: Int): String? {
        for (c in base.classMap.values) {
            val f: DvmField? = try {
                c.getField(hash) ?: c.getStaticField(hash)
            } catch (e: Exception) {
                null
            }
            if (f != null) {
                val sb = StringBuilder()
                sb.append("  signature = ").append(f.getSignature()).append('\n')
                sb.append("  static = ").append(f.isStatic()).append('\n')
                sb.append("  declaring = ").append(c.getClassName()).append('\n')
                return sb.toString()
            }
        }
        return null
    }

    // ---------- tool 2: dvm_handle_to_native ----------

    private fun handleToNative(args: JSONObject): JSONObject {
        val hash = DvmSupport.parseHashInt(args.getString("hash"))
                ?: return McpTools.errorResult("Missing required parameter 'hash'.")
        val base = DvmSupport.baseVm(vm)
        val sb = StringBuilder()
        sb.append("DVM handle 0x").append(Integer.toHexString(hash)).append(" (").append(hash).append(")\n")
        // The native side receives the hash as the jobject peer; the .so observes it zero-extended to 64-bit.
        val peer64 = hash.toLong() and 0xffffffffL
        sb.append("  native peer (32-bit) = 0x").append(Integer.toHexString(hash)).append('\n')
        sb.append("  native peer (jobject, 64-bit) = 0x").append(java.lang.Long.toHexString(peer64)).append('\n')
        sb.append("  ").append(scopeAndRef(base, hash)).append('\n')
        val obj: DvmObject<*>? = try {
            base.getObject<DvmObject<*>>(hash)
        } catch (e: Exception) {
            null
        }
        if (obj != null) {
            sb.append("  class = ").append(DvmSupport.classNameOf(obj)).append('\n')
            val preview = DvmSupport.valuePreview(obj)
            if (preview != null) sb.append("  value = ").append(preview).append('\n')
        }
        return McpTools.textResult(sb.toString())
    }

    // ---------- tool 3: dvm_args_at_breakpoint ----------

    private fun argsAtBreakpoint(args: JSONObject): JSONObject {
        if (emulator.isRunning()) {
            return McpTools.errorResult("The emulator is running; stop at a breakpoint to read argument registers.")
        }
        val count = parseIntOr(args, "count", 8)
        if (count <= 0) return McpTools.errorResult("'count' must be positive.")
        val ctx: RegisterContext = emulator.getContext()
        val base = DvmSupport.baseVm(vm)

        // Optional signature -> declared argument types (labels start at slot 2 per JNI convention).
        var argTypes: List<String>? = null
        val sig = args.getString("signature")
        if (sig != null && sig.isNotEmpty()) {
            val open = sig.indexOf('(')
            val close = sig.indexOf(')')
            val descriptor = if (open >= 0 && close > open) sig.substring(open + 1, close) else sig
            argTypes = try {
                DvmSupport.parseArgTypes(descriptor)
            } catch (e: Exception) {
                null
            }
        }

        val sb = StringBuilder()
        sb.append("JNI call args (").append(count).append(" slots). Convention: slot0=JNIEnv*, ")
                .append("slot1=this/jclass, then declared Java args.\n")
        for (i in 0 until count) {
            val p = ctx.getPointerArg(i)
            val peer = p?.peer ?: ctx.getLongArg(i)
            sb.append("  arg").append(i).append(" = 0x").append(java.lang.Long.toHexString(peer))
            // Label well-known and declared slots.
            val label = slotLabel(i, argTypes)
            if (label != null) sb.append("  [").append(label).append(']')
            // Try to resolve as a DVM object (slots 1+ may be jobject/jclass).
            if (i >= 1) {
                val obj: DvmObject<*>? = try {
                    base.getObject<DvmObject<*>>(peer.toInt())
                } catch (e: Exception) {
                    null
                }
                if (obj != null) {
                    sb.append(" -> ").append(DvmSupport.classNameOf(obj))
                    val preview = DvmSupport.valuePreview(obj)
                    if (preview != null) sb.append(" = ").append(preview)
                }
            }
            sb.append('\n')
        }
        return McpTools.textResult(sb.toString())
    }

    /** Label for an argument slot given the JNI convention and an optional declared-arg type list. */
    private fun slotLabel(slot: Int, argTypes: List<String>?): String? {
        if (slot == 0) return "JNIEnv*"
        if (slot == 1) return "this/jclass"
        if (argTypes != null) {
            val idx = slot - 2
            if (idx >= 0 && idx < argTypes.size) return argTypes[idx]
        }
        return null
    }

    // ---------- tool 4: dvm_class_of_native ----------

    private fun classOfNative(args: JSONObject): JSONObject {
        val base = DvmSupport.baseVm(vm)
        val value = args.getString("value")
        val symbol = args.getString("symbol")

        if (value != null && value.isNotEmpty()) {
            val hash = parseHandle(value)
                    ?: return McpTools.errorResult("Could not parse 'value' as a jclass peer: $value")
            val obj: DvmObject<*>? = try {
                base.getObject<DvmObject<*>>(hash)
            } catch (e: Exception) {
                null
            }
            if (obj == null) {
                return McpTools.errorResult("No live DVM object for jclass peer 0x" + Integer.toHexString(hash) + ".")
            }
            if (obj !is DvmClass) {
                return McpTools.errorResult("Peer 0x" + Integer.toHexString(hash) + " is not a jclass (it is " +
                        DvmSupport.classNameOf(obj) + ").")
            }
            return McpTools.textResult(renderClassNatives(obj))
        }

        if (symbol != null && symbol.isNotEmpty()) {
            return McpTools.textResult(matchSymbol(base, symbol.trim()))
        }

        return McpTools.errorResult("Provide either 'value' (jclass peer) or 'symbol' (Java_ mangled name).")
    }

    private fun renderClassNatives(cls: DvmClass): String {
        val sb = StringBuilder()
        sb.append("jclass = ").append(cls.getClassName()).append('\n')
        val natives = cls.nativesMap
        sb.append("registered natives (").append(natives.size).append("):\n")
        if (natives.isEmpty()) {
            sb.append("  (none registered via RegisterNatives; functions may bind lazily by Java_ symbol)\n")
        } else {
            for ((msig, ptr) in natives) {
                sb.append("  ").append(msig).append(" -> ").append(ptr).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * Best-effort match of a Java_ mangled symbol against each class's registered nativesMap. We
     * cannot fully demangle (the JNI name escapes _/;/[ and overloaded names append the arg sig), so
     * we build each class's expected Java_<class>_ prefix and test the symbol against it, then list
     * that class's natives. Reported as best-effort.
     */
    private fun matchSymbol(base: BaseVM, symbol: String): String {
        val sb = StringBuilder()
        sb.append("symbol = ").append(symbol).append(" (best-effort match)\n")
        var found = false
        for (c in base.classMap.values) {
            val prefix = "Java_" + mangleForJni(c.getClassName()) + "_"
            if (symbol.startsWith(prefix)) {
                found = true
                sb.append("owning class = ").append(c.getClassName()).append('\n')
                // Remaining segment after the class prefix is the mangled method name (+ overload sig).
                val rest = symbol.substring(prefix.length)
                sb.append("  mangled method tail = ").append(rest).append('\n')
                val natives = c.nativesMap
                if (natives.isNotEmpty()) {
                    sb.append("  RegisterNatives bindings on this class (").append(natives.size).append("):\n")
                    for ((msig, ptr) in natives) {
                        sb.append("    ").append(msig).append(" -> ").append(ptr).append('\n')
                    }
                } else {
                    sb.append("  (no RegisterNatives bindings; resolves by symbol lookup in loaded modules)\n")
                }
            }
        }
        if (!found) {
            sb.append("no resolved DVM class produces this Java_ prefix; the class may not be resolved yet, ")
                    .append("or the symbol belongs to a non-JNI export.\n")
        }
        return sb.toString()
    }

    // ---------- private helpers ----------

    /** Parse a native peer either as a 0x-hex/decimal Long (then truncate) or as a hash Int. */
    private fun parseHandle(raw: String): Int? {
        return try {
            DvmSupport.peerToHash(DvmSupport.parseLong(raw))
        } catch (e: Exception) {
            DvmSupport.parseHashInt(raw)
        }
    }

    private fun parseIntOr(args: JSONObject, key: String, def: Int): Int {
        val v = args.get(key) ?: return def
        return when (v) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull() ?: def
            else -> def
        }
    }

    /** Probe the three DVM ref maps and report scope + refCount for a hash. */
    private fun scopeAndRef(base: BaseVM, hash: Int): String {
        base.localObjectMap[hash]?.let {
            return "scope = local, refCount = " + it.refCount
        }
        base.globalObjectMap[hash]?.let {
            return "scope = global, refCount = " + it.refCount
        }
        base.weakGlobalObjectMap[hash]?.let {
            return "scope = weak-global, refCount = " + it.refCount
        }
        return "scope = none (not present in local/global/weak maps)"
    }

    /**
     * JNI name mangling for a class/method segment, mirroring DvmClass.mangleForJni: alphanumerics
     * pass through, '/' and '.' -> '_', '_' -> "_1", ';' -> "_2", '[' -> "_3", else "_0xxxx".
     */
    private fun mangleForJni(name: String): String {
        val builder = StringBuilder()
        for (c in name.toCharArray()) {
            if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9')) {
                builder.append(c)
            } else if (c == '.' || c == '/') {
                builder.append("_")
            } else if (c == '_') {
                builder.append("_1")
            } else if (c == ';') {
                builder.append("_2")
            } else if (c == '[') {
                builder.append("_3")
            } else {
                builder.append(String.format("_0%04x", c.code and 0xffff))
            }
        }
        return builder.toString()
    }
}
