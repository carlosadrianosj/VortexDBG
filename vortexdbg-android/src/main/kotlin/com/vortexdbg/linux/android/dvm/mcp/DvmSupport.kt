package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.BaseVM
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.StringObject
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.linux.android.dvm.array.ByteArray as DvmByteArray
import org.apache.commons.codec.binary.Hex

/**
 * Shared helpers for the DVM/Java MCP sub-handlers: schema/param builders, hash/peer parsing,
 * value previews, JNI-signature arg marshalling (with `@handle` support) and static-call dispatch.
 * Stateless; pass `vm`/`emulator` into the methods that need them.
 */
object DvmSupport {

    fun baseVm(vm: VM): BaseVM =
            vm as? BaseVM ?: throw IllegalStateException("VM is not a BaseVM, cannot inspect DVM state")

    // ---------- schema helpers ----------

    fun schema(name: String, description: String, vararg params: JSONObject): JSONObject {
        val s = JSONObject(true)
        s.put("name", name)
        s.put("description", description)
        val input = JSONObject(true)
        input.put("type", "object")
        if (params.isNotEmpty()) {
            val props = JSONObject(true)
            for (p in params) {
                props.put(p.getString("_name"), p)
                p.remove("_name")
            }
            input.put("properties", props)
        }
        s.put("inputSchema", input)
        return s
    }

    fun param(name: String, description: String, type: String = "string"): JSONObject {
        val p = JSONObject(true)
        p.put("_name", name)
        p.put("type", type)
        p.put("description", description)
        if (type == "array") {
            val items = JSONObject(true)
            items.put("type", "string")
            p.put("items", items)
        }
        return p
    }

    // ---------- hashes / peers ----------

    /** Parse a decimal or 0x-hex JNI hash handle (a 32-bit peer). */
    fun parseHashInt(raw: String?): Int? {
        if (raw == null) return null
        val s = raw.trim()
        return if (s.startsWith("0x") || s.startsWith("0X")) {
            Integer.parseUnsignedInt(s.substring(2), 16)
        } else {
            Integer.parseInt(s)
        }
    }

    /** A native jobject/jmethodID/jfieldID peer truncated to the Int hash used in the DVM maps. */
    fun peerToHash(peer: Long): Int = peer.toInt()

    fun parseLong(raw: String): Long {
        val s = raw.trim()
        return if (s.startsWith("0x") || s.startsWith("0X")) {
            java.lang.Long.parseLong(s.substring(2), 16)
        } else {
            java.lang.Long.parseLong(s)
        }
    }

    // ---------- previews / rendering ----------

    fun valuePreview(obj: DvmObject<*>?): String? {
        if (obj == null) return null
        val value = try {
            obj.getValue()
        } catch (e: Exception) {
            return null
        } ?: return null
        return when (value) {
            is String -> "\"$value\""
            is kotlin.ByteArray -> "bytes[" + value.size + "] " + Hex.encodeHexString(value)
            else -> value.toString()
        }
    }

    fun formatObjectResult(obj: DvmObject<*>?): String {
        if (obj == null) return "null"
        val value = obj.getValue() ?: return "null"
        return when (value) {
            is String -> "\"$value\""
            is kotlin.ByteArray -> Hex.encodeHexString(value) + " (" + value.size + " bytes)"
            else -> value.toString()
        }
    }

    fun classNameOf(obj: DvmObject<*>): String = try {
        obj.getObjectType()?.getClassName() ?: obj.javaClass.simpleName
    } catch (e: Exception) {
        obj.javaClass.simpleName
    }

    // ---------- JNI signature parsing / arg marshalling ----------

    fun parseArgTypes(descriptor: String): List<String> {
        val types = ArrayList<String>()
        var i = 0
        while (i < descriptor.length) {
            when (descriptor[i]) {
                'L' -> {
                    val end = descriptor.indexOf(';', i)
                    types.add(descriptor.substring(i, end + 1))
                    i = end + 1
                }
                '[' -> {
                    var j = i
                    while (descriptor[j] == '[') j++
                    if (descriptor[j] == 'L') {
                        val end = descriptor.indexOf(';', j)
                        types.add(descriptor.substring(i, end + 1))
                        i = end + 1
                    } else {
                        types.add(descriptor.substring(i, j + 1))
                        i = j + 1
                    }
                }
                else -> {
                    types.add(descriptor[i].toString())
                    i++
                }
            }
        }
        return types
    }

    /**
     * Convert a single string-encoded argument per its JNI type. Object/array slots may be passed
     * as `@0x..` / `@<dec>` to reference an existing DVM handle via [VM.getObject].
     */
    fun convertArg(vm: VM, type: String, raw: String): Any? {
        if ((type.startsWith("L") || type.startsWith("[")) && raw.startsWith("@")) {
            val hash = parseHashInt(raw.substring(1))
                    ?: throw IllegalArgumentException("Bad @handle argument: $raw")
            return baseVm(vm).getObject<DvmObject<*>>(hash)
        }
        return when {
            type == "Ljava/lang/String;" -> StringObject(vm, raw)
            type == "[B" -> DvmByteArray(vm, Hex.decodeHex(raw.toCharArray()))
            type == "Z" -> (raw == "true" || raw == "1")
            type == "J" -> parseLong(raw)
            type == "I" || type == "S" || type == "B" -> parseLong(raw).toInt()
            type == "C" -> if (raw.length == 1) raw[0].code else parseLong(raw).toInt()
            type == "F" -> raw.toFloat()
            type == "D" -> raw.toDouble()
            type.startsWith("L") -> StringObject(vm, raw)
            else -> parseLong(raw)
        }
    }

    /** Build the call-args array from a JNI signature and a JSON array of string args. */
    fun buildArgs(vm: VM, method: String, argArray: JSONArray?): Array<Any?> {
        val open = method.indexOf('(')
        val close = method.indexOf(')')
        require(open >= 0 && close > open) { "Invalid method signature: $method" }
        val argTypes = parseArgTypes(method.substring(open + 1, close))
        val provided = argArray?.size ?: 0
        require(provided == argTypes.size) { "Signature expects ${argTypes.size} args but got $provided." }
        val callArgs = arrayOfNulls<Any?>(argTypes.size)
        for (i in argTypes.indices) {
            callArgs[i] = convertArg(vm, argTypes[i], argArray!!.getString(i))
        }
        return callArgs
    }

    class CallResult(@JvmField val rendered: String, @JvmField val rawValue: Any?)

    /** Dispatch a static native-registered method by JNI signature; returns rendered + raw result. */
    fun callStatic(emulator: Emulator<*>, vm: VM, className: String, method: String, argArray: JSONArray?): CallResult {
        val close = method.indexOf(')')
        val retDescriptor = method.substring(close + 1)
        require(retDescriptor.isNotEmpty()) { "Invalid method signature (no return type): $method" }
        val callArgs = buildArgs(vm, method, argArray)
        val cls: DvmClass = vm.resolveClass(className)
        return when (retDescriptor[0]) {
            'V' -> { cls.callStaticJniMethod(emulator, method, *callArgs); CallResult("void", null) }
            'Z' -> { val v = cls.callStaticJniMethodBoolean(emulator, method, *callArgs); CallResult(v.toString(), v) }
            'B', 'C', 'S', 'I' -> { val v = cls.callStaticJniMethodInt(emulator, method, *callArgs); CallResult(v.toString(), v) }
            'J' -> { val v = cls.callStaticJniMethodLong(emulator, method, *callArgs); CallResult(v.toString(), v) }
            'L', '[' -> {
                val o = cls.callStaticJniMethodObject<DvmObject<*>>(emulator, method, *callArgs)
                CallResult(formatObjectResult(o), o?.getValue())
            }
            else -> throw IllegalArgumentException("Unsupported return type: $retDescriptor")
        }
    }

    /** Dispatch an INSTANCE native-registered method on [obj] by JNI signature. */
    fun callInstance(emulator: Emulator<*>, vm: VM, obj: DvmObject<*>, method: String, argArray: JSONArray?): CallResult {
        val close = method.indexOf(')')
        val retDescriptor = method.substring(close + 1)
        require(retDescriptor.isNotEmpty()) { "Invalid method signature (no return type): $method" }
        val callArgs = buildArgs(vm, method, argArray)
        return when (retDescriptor[0]) {
            'V' -> { obj.callJniMethod(emulator, method, *callArgs); CallResult("void", null) }
            'Z' -> { val v = obj.callJniMethodBoolean(emulator, method, *callArgs); CallResult(v.toString(), v) }
            'B', 'C', 'S', 'I' -> { val v = obj.callJniMethodInt(emulator, method, *callArgs); CallResult(v.toString(), v) }
            'J' -> { val v = obj.callJniMethodLong(emulator, method, *callArgs); CallResult(v.toString(), v) }
            'L', '[' -> {
                val o = obj.callJniMethodObject<DvmObject<*>>(emulator, method, *callArgs)
                CallResult(formatObjectResult(o), o?.getValue())
            }
            else -> throw IllegalArgumentException("Unsupported return type: $retDescriptor")
        }
    }

    /** Register an object as a GLOBAL ref (survives deleteLocalRefs) and return its JNI hash handle. */
    fun registerGlobal(vm: VM, obj: DvmObject<*>): Int = baseVm(vm).addGlobalObject(obj)
}
