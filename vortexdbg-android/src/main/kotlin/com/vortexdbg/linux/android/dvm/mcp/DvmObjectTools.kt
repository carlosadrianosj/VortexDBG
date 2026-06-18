package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.StringObject
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.linux.android.dvm.array.ArrayObject
import com.vortexdbg.linux.android.dvm.array.ByteArray as DvmByteArray
import com.vortexdbg.linux.android.dvm.array.DoubleArray as DvmDoubleArray
import com.vortexdbg.linux.android.dvm.array.FloatArray as DvmFloatArray
import com.vortexdbg.linux.android.dvm.array.IntArray as DvmIntArray
import com.vortexdbg.linux.android.dvm.array.ShortArray as DvmShortArray
import com.vortexdbg.mcp.McpTools
import org.apache.commons.codec.binary.Hex

/**
 * [DvmSubTools] group for CREATING and OPERATING ON live DVM/Java objects from the host JVM side.
 *
 * Tools:
 *  - `dvm_make_object`: build a primitive-backed DVM object (string / byte[] / int[] / short[] / float[] / double[]).
 *  - `dvm_call_instance`: invoke an instance JNI method on an existing object handle.
 *  - `dvm_new_object`: allocate an object of a class and run its <init> constructor.
 *  - `dvm_new_array_object`: build an Object[] (array.ArrayObject) from strings and/or @handle refs.
 *  - `dvm_pin_ref`: promote an existing handle to a global ref so it survives deleteLocalRefs().
 *  - `dvm_release_ref`: drop a global/weak ref for a handle.
 *
 * Objects created by make/new/new_array are registered as GLOBAL refs (so they survive the local-ref
 * wipe after each JNI call) and their JNI hash handle is returned so it can be reused via `@handle`.
 */
class DvmObjectTools(private val emulator: Emulator<*>, private val vm: VM) : DvmSubTools {

    private val names = setOf(
            "dvm_make_object",
            "dvm_call_instance",
            "dvm_new_object",
            "dvm_new_array_object",
            "dvm_pin_ref",
            "dvm_release_ref")

    override fun handles(name: String): Boolean = names.contains(name)

    override fun schemas(): JSONArray {
        val tools = JSONArray()
        tools.add(DvmSupport.schema("dvm_make_object",
                "[DVM/Java] Create a primitive-backed DVM object on the host JVM and register it as a GLOBAL ref " +
                        "(so it survives deleteLocalRefs after JNI calls). Returns the JNI hash handle (0x-hex + decimal) " +
                        "you can reuse as an @handle argument elsewhere. type=string builds a StringObject from 'value'; " +
                        "type=bytes builds a byte[] from a hex string in 'value'; type=int[]/short[]/float[]/double[] build " +
                        "primitive arrays from 'value' given as a comma-separated list or a JSON array of numbers.",
                DvmSupport.param("type", "One of: string, bytes, int[], short[], float[], double[]."),
                DvmSupport.param("value", "For string: the text. For bytes: a hex string. For arrays: comma-separated numbers or a JSON array.")))
        tools.add(DvmSupport.schema("dvm_call_instance",
                "[DVM/Java] Invoke an INSTANCE native-registered method on an existing DVM object by its JNI hash handle. " +
                        "Requires the emulator stopped. 'method' is a JNI signature like getName()Ljava/lang/String;. " +
                        "Object/array argument slots may be passed as @0x-hex / @decimal handles.",
                DvmSupport.param("hash", "JNI hash handle (decimal or 0x-hex) of the receiver object."),
                DvmSupport.param("method", "JNI method signature, e.g. update([B)V or hashCode()I."),
                DvmSupport.param("args", "Optional. JSON array of string-encoded arguments matching the signature (@handle for objects).", "array")))
        tools.add(DvmSupport.schema("dvm_new_object",
                "[DVM/Java] Allocate an instance of 'class' and run its constructor. Requires the emulator stopped. " +
                        "'method' is a constructor JNI signature like <init>(Ljava/lang/String;)V. The new object is " +
                        "registered as a GLOBAL ref and its JNI hash handle (0x-hex + decimal) is returned for reuse via @handle.",
                DvmSupport.param("class", "JNI class name, e.g. java/lang/String."),
                DvmSupport.param("method", "Constructor JNI signature, e.g. <init>(Ljava/lang/String;)V."),
                DvmSupport.param("args", "Optional. JSON array of string-encoded constructor arguments (@handle for objects).", "array")))
        tools.add(DvmSupport.schema("dvm_new_array_object",
                "[DVM/Java] Build a Java Object[] (array.ArrayObject) and register it as a GLOBAL ref; returns its JNI " +
                        "hash handle (0x-hex + decimal). 'elements' is a JSON array whose entries are either @0x-hex / " +
                        "@decimal handles (resolved to existing DVM objects) or plain strings (wrapped as StringObject).",
                DvmSupport.param("elements", "JSON array of @handle references and/or plain strings.", "array")))
        tools.add(DvmSupport.schema("dvm_pin_ref",
                "[DVM/Java] Promote an existing DVM object handle to a GLOBAL ref so it survives the local-ref wipe " +
                        "(deleteLocalRefs) performed after each JNI call. Returns the JNI hash handle.",
                DvmSupport.param("hash", "JNI hash handle (decimal or 0x-hex) of the object to pin.")))
        tools.add(DvmSupport.schema("dvm_release_ref",
                "[DVM/Java] Drop the global (and weak-global) ref entry for a JNI hash handle, undoing a dvm_pin_ref / " +
                        "make/new registration. Reports whether an entry was removed.",
                DvmSupport.param("hash", "JNI hash handle (decimal or 0x-hex) of the object to release.")))
        return tools
    }

    override fun call(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "dvm_make_object" -> makeObject(args)
                "dvm_call_instance" -> callInstance(args)
                "dvm_new_object" -> newObject(args)
                "dvm_new_array_object" -> newArrayObject(args)
                "dvm_pin_ref" -> pinRef(args)
                "dvm_release_ref" -> releaseRef(args)
                else -> McpTools.errorResult("Unknown DVM object tool: $name")
            }
        } catch (e: Exception) {
            McpTools.errorResult("DVM object tool '$name' failed: " + (e.message ?: e.javaClass.name))
        }
    }

    // ---------- helpers ----------

    private fun handleText(prefix: String, obj: DvmObject<*>, hash: Int): JSONObject {
        val sb = StringBuilder()
        sb.append(prefix).append('\n')
        sb.append("  class = ").append(DvmSupport.classNameOf(obj)).append('\n')
        val preview = DvmSupport.valuePreview(obj)
        if (preview != null) sb.append("  value = ").append(preview).append('\n')
        sb.append("  handle = 0x").append(Integer.toHexString(hash)).append(" (").append(hash).append(")\n")
        sb.append("  (registered as a global ref; reuse as @0x").append(Integer.toHexString(hash)).append(")")
        return McpTools.textResult(sb.toString())
    }

    /** Split a comma-separated list or a JSON array string into number tokens. */
    private fun numberTokens(raw: String): List<String> {
        val s = raw.trim()
        if (s.isEmpty()) return emptyList()
        if (s.startsWith("[")) {
            val arr = JSONArray.parseArray(s)
            val out = ArrayList<String>(arr.size)
            for (i in 0 until arr.size) out.add(arr.getString(i))
            return out
        }
        val out = ArrayList<String>()
        for (tok in s.split(",")) {
            val t = tok.trim()
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    // ---------- tool 1: dvm_make_object ----------

    private fun makeObject(args: JSONObject): JSONObject {
        val type = args.getString("type")
        if (type == null || type.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'type' (string|bytes|int[]|short[]|float[]|double[]).")
        }
        val value = args.getString("value") ?: ""
        val obj: DvmObject<*> = when (type.trim().toLowerCase()) {
            "string" -> StringObject(vm, value)
            "bytes" -> DvmByteArray(vm, Hex.decodeHex(value.trim().toCharArray()))
            "int[]", "int", "i", "[i" -> {
                val toks = numberTokens(value)
                val a = kotlin.IntArray(toks.size)
                for (i in toks.indices) a[i] = toks[i].toInt()
                DvmIntArray(vm, a)
            }
            "short[]", "short", "s", "[s" -> {
                val toks = numberTokens(value)
                val a = kotlin.ShortArray(toks.size)
                for (i in toks.indices) a[i] = toks[i].toShort()
                DvmShortArray(vm, a)
            }
            "float[]", "float", "f", "[f" -> {
                val toks = numberTokens(value)
                val a = kotlin.FloatArray(toks.size)
                for (i in toks.indices) a[i] = toks[i].toFloat()
                DvmFloatArray(vm, a)
            }
            "double[]", "double", "d", "[d" -> {
                val toks = numberTokens(value)
                val a = kotlin.DoubleArray(toks.size)
                for (i in toks.indices) a[i] = toks[i].toDouble()
                DvmDoubleArray(vm, a)
            }
            else -> return McpTools.errorResult("Unsupported 'type': $type (expected string|bytes|int[]|short[]|float[]|double[]).")
        }
        val hash = DvmSupport.registerGlobal(vm, obj)
        return handleText("Created object (type=$type).", obj, hash)
    }

    // ---------- tool 2: dvm_call_instance ----------

    private fun callInstance(args: JSONObject): JSONObject {
        if (emulator.isRunning()) {
            return McpTools.errorResult("Emulator is running; stop it first.")
        }
        val hash = DvmSupport.parseHashInt(args.getString("hash"))
                ?: return McpTools.errorResult("Missing required parameter 'hash'.")
        val method = args.getString("method")
        if (method == null || method.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'method' (JNI signature).")
        }
        val obj: DvmObject<*> = DvmSupport.baseVm(vm).getObject(hash)
                ?: return McpTools.errorResult("No object found for hash 0x" + Integer.toHexString(hash) + ".")
        val argArray = args.getJSONArray("args")
        val result = DvmSupport.callInstance(emulator, vm, obj, method.trim(), argArray)
        val sb = StringBuilder()
        sb.append("called ").append(DvmSupport.classNameOf(obj)).append("->").append(method.trim()).append('\n')
        sb.append("  result = ").append(result.rendered)
        return McpTools.textResult(sb.toString())
    }

    // ---------- tool 3: dvm_new_object ----------

    private fun newObject(args: JSONObject): JSONObject {
        if (emulator.isRunning()) {
            return McpTools.errorResult("Emulator is running; stop it first.")
        }
        val className = args.getString("class")
        if (className == null || className.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'class' (JNI class name).")
        }
        val method = args.getString("method")
        if (method == null || method.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'method' (constructor JNI signature, e.g. <init>(Ljava/lang/String;)V).")
        }
        val argArray = args.getJSONArray("args")
        val cls: DvmClass = vm.resolveClass(className.trim())
        val obj: DvmObject<*> = cls.allocObject()
                ?: return McpTools.errorResult("allocObject() returned null for class " + className.trim() + ".")
        try {
            obj.callJniMethod(emulator, method.trim(), *DvmSupport.buildArgs(vm, method.trim(), argArray))
        } catch (e: Exception) {
            return McpTools.errorResult("allocObject succeeded but constructor " + method.trim() +
                    " failed: " + (e.message ?: e.javaClass.name) +
                    " (the class may need a real JVM-backed implementation to construct).")
        }
        val hash = DvmSupport.registerGlobal(vm, obj)
        return handleText("Constructed " + className.trim() + " via " + method.trim() + ".", obj, hash)
    }

    // ---------- tool 4: dvm_new_array_object ----------

    private fun newArrayObject(args: JSONObject): JSONObject {
        val elements = args.getJSONArray("elements")
                ?: return McpTools.errorResult("Missing required parameter 'elements' (JSON array of @handle refs and/or strings).")
        val base = DvmSupport.baseVm(vm)
        val objs = arrayOfNulls<DvmObject<*>?>(elements.size)
        for (i in 0 until elements.size) {
            val raw = elements.getString(i)
            objs[i] = if (raw != null && raw.startsWith("@")) {
                val h = DvmSupport.parseHashInt(raw.substring(1))
                        ?: return McpTools.errorResult("Bad @handle element at index $i: $raw")
                base.getObject<DvmObject<*>>(h)
                        ?: return McpTools.errorResult("No object found for handle in element $i: $raw")
            } else {
                StringObject(vm, raw ?: "")
            }
        }
        val arr = ArrayObject(*objs)
        val hash = DvmSupport.registerGlobal(vm, arr)
        return handleText("Created Object[" + elements.size + "].", arr, hash)
    }

    // ---------- tool 5: dvm_pin_ref ----------

    private fun pinRef(args: JSONObject): JSONObject {
        val hash = DvmSupport.parseHashInt(args.getString("hash"))
                ?: return McpTools.errorResult("Missing required parameter 'hash'.")
        val obj: DvmObject<*> = DvmSupport.baseVm(vm).getObject(hash)
                ?: return McpTools.errorResult("No object found for hash 0x" + Integer.toHexString(hash) + ".")
        val newHash = DvmSupport.registerGlobal(vm, obj)
        val sb = StringBuilder()
        sb.append("pinned 0x").append(Integer.toHexString(hash)).append(" as a global ref.\n")
        sb.append("  class = ").append(DvmSupport.classNameOf(obj)).append('\n')
        sb.append("  handle = 0x").append(Integer.toHexString(newHash)).append(" (").append(newHash).append(")")
        return McpTools.textResult(sb.toString())
    }

    // ---------- tool 6: dvm_release_ref ----------

    private fun releaseRef(args: JSONObject): JSONObject {
        val hash = DvmSupport.parseHashInt(args.getString("hash"))
                ?: return McpTools.errorResult("Missing required parameter 'hash'.")
        val base = DvmSupport.baseVm(vm)
        val removedGlobal = base.globalObjectMap.remove(hash) != null
        val removedWeak = base.weakGlobalObjectMap.remove(hash) != null
        val sb = StringBuilder()
        sb.append("release 0x").append(Integer.toHexString(hash)).append(" (").append(hash).append(")\n")
        if (!removedGlobal && !removedWeak) {
            sb.append("  not found in global/weak-global maps (nothing removed).")
        } else {
            if (removedGlobal) sb.append("  removed from global ref map.\n")
            if (removedWeak) sb.append("  removed from weak-global ref map.\n")
            sb.setLength(sb.length - 1)
        }
        return McpTools.textResult(sb.toString())
    }
}
