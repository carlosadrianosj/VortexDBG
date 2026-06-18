package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.BaseVM
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmField
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.StringObject
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.linux.android.dvm.array.BaseArray
import com.vortexdbg.mcp.McpTools
import org.apache.commons.codec.binary.Hex

/**
 * DVM/Java MCP sub-handler for reading array contents, reading/writing fields and stringifying objects.
 *
 * Tools:
 *  - `dvm_read_array`: dump the typed contents of a DVM array object (ByteArray/IntArray/ShortArray/FloatArray/
 *    DoubleArray/Object[]) addressed by JNI hash, with truncation.
 *  - `dvm_read_field`: read a static field (by class + name + type) or an instance field (by target object hash).
 *    Field access is serviced by the registered native JNI bridge (the loaded .so's Get*Field handlers), so it only
 *    works for fields the native code actually backs.
 *  - `dvm_set_field`: write a static or instance field through the same native JNI bridge (Set*Field handlers).
 *  - `dvm_to_string`: best-effort String rendering of an object; tries the registered native toString(), then
 *    falls back to a value preview / host toString.
 *
 * IMPORTANT: field read/write and native toString go through the emulated .so; they must run while the emulator is
 * stopped, and they only succeed for members the native Jni implementation accepts/backs. Pure host-side DVM objects
 * (e.g. a StringObject created by the tooling) typically have no native field/toString handlers.
 */
class DvmFieldTools(private val emulator: Emulator<*>, private val vm: VM) : DvmSubTools {

    private val toolNames = setOf("dvm_read_array", "dvm_read_field", "dvm_set_field", "dvm_to_string")

    override fun handles(name: String): Boolean = toolNames.contains(name)

    override fun schemas(): JSONArray {
        val tools = JSONArray()
        tools.add(DvmSupport.schema("dvm_read_array",
                "[DVM/Java] Read the typed contents of a DVM array object addressed by its JNI hash. Supports primitive " +
                        "arrays (byte->hex, int/short/float/double->list) and Object[] (per-element class + value preview). " +
                        "Output is truncated to 'max' elements (default 256); the full length is always reported.",
                DvmSupport.param("hash", "JNI hash of the array object (decimal or 0x-hex)."),
                DvmSupport.param("max", "Optional. Maximum number of elements (or bytes) to print; default 256.", "integer")))
        tools.add(DvmSupport.schema("dvm_read_field",
                "[DVM/Java] Read a field value through the registered native JNI bridge (the loaded .so's Get*Field handlers). " +
                        "Static field: pass 'class' (e.g. com/foo/Bar), 'field' and 'type' (JNI descriptor, e.g. I, Z, J, " +
                        "Ljava/lang/String;). Instance field: also pass 'target_hash' (the object's JNI hash). Only works for " +
                        "fields the native code backs; host-only objects usually have no field handlers. Supported types: " +
                        "Z,B,I,S,C,J,L (static); Z,B,I,S,C,J,F,L (instance). Stop the emulator before calling.",
                DvmSupport.param("class", "Declaring class in JNI form (slash-separated), e.g. com/example/Foo."),
                DvmSupport.param("field", "Field name."),
                DvmSupport.param("type", "Field JNI type descriptor, e.g. I, Z, J, F, Ljava/lang/String;, [B."),
                DvmSupport.param("target_hash", "Optional. JNI hash of the instance whose field to read; omit for a static field.")))
        tools.add(DvmSupport.schema("dvm_set_field",
                "[DVM/Java] Write a field value through the registered native JNI bridge (Set*Field handlers). Static field: " +
                        "pass 'class', 'field', 'type', 'value'. Instance field: pass 'target_hash', 'field', 'type', 'value'. " +
                        "Object values may be a @handle (e.g. @0x123 referencing an existing DVM object) or a plain string " +
                        "(wrapped as a String object). Supported types: Z,I,S,C,B,J,F,D,L (both static and instance). Byte/char/" +
                        "short use the int setter; there is no dedicated byte/char/short setter in the Jni bridge. Stop the emulator before calling.",
                DvmSupport.param("class", "Declaring class in JNI form (for a static field). Omit when writing an instance field."),
                DvmSupport.param("target_hash", "JNI hash of the instance (for an instance field). Omit when writing a static field."),
                DvmSupport.param("field", "Field name."),
                DvmSupport.param("type", "Field JNI type descriptor, e.g. I, Z, J, F, D, Ljava/lang/String;."),
                DvmSupport.param("value", "New value: primitives as text (true/false, numbers); objects as @handle or a string.")))
        tools.add(DvmSupport.schema("dvm_to_string",
                "[DVM/Java] Best-effort String rendering of a DVM object by JNI hash. First tries the registered native " +
                        "toString()Ljava/lang/String; (requires the emulator stopped and a native handler); if that is absent " +
                        "or fails (the common case — toString is rarely native-registered), falls back to a value preview / host toString.",
                DvmSupport.param("hash", "JNI hash of the object (decimal or 0x-hex).")))
        return tools
    }

    override fun call(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "dvm_read_array" -> doReadArray(args)
                "dvm_read_field" -> doReadField(args)
                "dvm_set_field" -> doSetField(args)
                "dvm_to_string" -> doToString(args)
                else -> McpTools.errorResult("Unknown DVM field tool: $name")
            }
        } catch (e: Exception) {
            McpTools.errorResult("DVM field tool '$name' failed: " + (e.message ?: e.javaClass.name))
        }
    }

    // ---------- dvm_read_array ----------

    private fun doReadArray(args: JSONObject): JSONObject {
        val hash = DvmSupport.parseHashInt(args.getString("hash"))
                ?: return McpTools.errorResult("Missing/invalid 'hash' (decimal or 0x-hex).")
        val max = parseMax(args.getString("max"), 256)
        val base = DvmSupport.baseVm(vm)
        val obj: DvmObject<*> = base.getObject(hash)
                ?: return McpTools.errorResult("No object found for hash 0x" + Integer.toHexString(hash) + ".")
        if (obj !is BaseArray<*>) {
            return McpTools.errorResult("Object 0x" + Integer.toHexString(hash) + " is not an array (it is " +
                    DvmSupport.classNameOf(obj) + ").")
        }
        val arr = obj as BaseArray<*>
        val length = arr.length()
        val value: Any? = try {
            arr.getValue()
        } catch (e: Exception) {
            return McpTools.errorResult("Failed to read array value: " + (e.message ?: e.javaClass.name))
        }
        val sb = StringBuilder()
        sb.append("array 0x").append(Integer.toHexString(hash)).append(" class=").append(DvmSupport.classNameOf(obj))
                .append(" length=").append(length).append('\n')
        when (value) {
            is kotlin.ByteArray -> {
                val n = Math.min(value.size, max)
                sb.append("type=byte[] ").append(value.size).append(" bytes")
                if (n < value.size) sb.append(" (showing first ").append(n).append(")")
                sb.append('\n')
                sb.append(Hex.encodeHexString(value.copyOfRange(0, n)))
            }
            is IntArray -> appendPrimitiveList(sb, "int[]", value.size, max) { i -> value[i].toString() }
            is ShortArray -> appendPrimitiveList(sb, "short[]", value.size, max) { i -> value[i].toString() }
            is FloatArray -> appendPrimitiveList(sb, "float[]", value.size, max) { i -> value[i].toString() }
            is DoubleArray -> appendPrimitiveList(sb, "double[]", value.size, max) { i -> value[i].toString() }
            is LongArray -> appendPrimitiveList(sb, "long[]", value.size, max) { i -> value[i].toString() }
            is BooleanArray -> appendPrimitiveList(sb, "boolean[]", value.size, max) { i -> value[i].toString() }
            is CharArray -> appendPrimitiveList(sb, "char[]", value.size, max) { i -> value[i].toString() }
            is kotlin.Array<*> -> {
                val elems = value
                val n = Math.min(elems.size, max)
                sb.append("type=Object[] ").append(elems.size).append(" elements")
                if (n < elems.size) sb.append(" (showing first ").append(n).append(")")
                sb.append('\n')
                for (i in 0 until n) {
                    val el = elems[i]
                    sb.append("  [").append(i).append("] ")
                    if (el is DvmObject<*>) {
                        sb.append(DvmSupport.classNameOf(el))
                        val pv = DvmSupport.valuePreview(el)
                        if (pv != null) sb.append(" = ").append(pv)
                    } else {
                        sb.append(if (el == null) "null" else el.toString())
                    }
                    sb.append('\n')
                }
            }
            null -> sb.append("(value is null)")
            else -> sb.append("type=").append(value.javaClass.name).append(" value=").append(value.toString())
        }
        return McpTools.textResult(sb.toString())
    }

    private inline fun appendPrimitiveList(sb: StringBuilder, typeLabel: String, size: Int, max: Int, render: (Int) -> String) {
        val n = Math.min(size, max)
        sb.append("type=").append(typeLabel).append(' ').append(size).append(" elements")
        if (n < size) sb.append(" (showing first ").append(n).append(")")
        sb.append('\n').append('[')
        for (i in 0 until n) {
            if (i > 0) sb.append(", ")
            sb.append(render(i))
        }
        if (n < size) sb.append(", ...")
        sb.append(']')
    }

    private fun parseMax(raw: String?, dflt: Int): Int {
        if (raw == null || raw.trim().isEmpty()) return dflt
        return try {
            val v = DvmSupport.parseLong(raw).toInt()
            if (v <= 0) dflt else v
        } catch (e: Exception) {
            dflt
        }
    }

    // ---------- dvm_read_field ----------

    private fun doReadField(args: JSONObject): JSONObject {
        if (emulator.isRunning()) return McpTools.errorResult("Emulator is running; stop it first.")
        val field = args.getString("field")
        if (field == null || field.trim().isEmpty()) return McpTools.errorResult("Missing required parameter 'field'.")
        val type = args.getString("type")
        if (type == null || type.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'type' (JNI descriptor, e.g. I, Z, Ljava/lang/String;).")
        }
        val base = DvmSupport.baseVm(vm)
        val targetRaw = args.getString("target_hash")
        return if (targetRaw != null && targetRaw.trim().isNotEmpty()) {
            readInstanceField(base, type, field, targetRaw)
        } else {
            readStaticField(args, type, field)
        }
    }

    private fun readStaticField(args: JSONObject, type: String, field: String): JSONObject {
        val className = args.getString("class")
        if (className == null || className.trim().isEmpty()) {
            return McpTools.errorResult("For a static field read, 'class' is required (or pass 'target_hash' for an instance field).")
        }
        val cls: DvmClass = vm.resolveClass(className)
        val hash = cls.getStaticFieldID(field, type)
        if (hash == 0) {
            return McpTools.errorResult("Static field not accepted by the native JNI bridge: " + className + "->" + field + ":" + type)
        }
        val dvmField: DvmField = cls.getStaticField(hash)
                ?: return McpTools.errorResult("Could not resolve static DvmField for " + className + "->" + field + ":" + type)
        val rendered: String = try {
            when (type[0]) {
                'Z' -> dvmField.getStaticBooleanField().toString()
                'B' -> dvmField.getStaticByteField().toString()
                'I', 'S', 'C' -> dvmField.getStaticIntField().toString()
                'J' -> dvmField.getStaticLongField().toString()
                'L', '[' -> DvmSupport.formatObjectResult(dvmField.getStaticObjectField())
                'F', 'D' -> return McpTools.errorResult("Static float/double field reads are not exposed by the Jni bridge (no getStaticFloatField/getStaticDoubleField).")
                else -> return McpTools.errorResult("Unsupported field type descriptor: $type")
            }
        } catch (e: Exception) {
            return McpTools.errorResult("Native static field read failed (likely no native handler for this field): " +
                    (e.message ?: e.javaClass.name))
        }
        return McpTools.textResult("static " + cls.getClassName() + "->" + field + ":" + type + " = " + rendered)
    }

    private fun readInstanceField(base: BaseVM, type: String, field: String, targetRaw: String): JSONObject {
        val targetHash = DvmSupport.parseHashInt(targetRaw)
                ?: return McpTools.errorResult("Invalid 'target_hash' (decimal or 0x-hex).")
        val target: DvmObject<*> = base.getObject(targetHash)
                ?: return McpTools.errorResult("No object found for target_hash 0x" + Integer.toHexString(targetHash) + ".")
        // Host-backed instance (e.g. a ProxyDvmObject wrapping a real host object): read the actual
        // host field reflectively. This is the working path under ProxyClassFactory.
        val host = hostInstance(target)
        if (host != null) {
            val f = findField(host.javaClass, field)
            if (f != null) {
                f.isAccessible = true
                return McpTools.textResult("instance 0x" + Integer.toHexString(targetHash) + "." + field +
                        ":" + type + " = " + renderHost(f.get(host)))
            }
        }
        val jni = base.jni
                ?: return McpTools.errorResult("No Jni bridge registered on the VM; cannot read instance fields.")
        // Signature form accepted by the Jni getter overloads: name:type
        val signature = field + ":" + type
        val rendered: String = try {
            when (type[0]) {
                'Z' -> jni.getBooleanField(base, target, signature).toString()
                'B' -> jni.getByteField(base, target, signature).toString()
                'I', 'S', 'C' -> jni.getIntField(base, target, signature).toString()
                'J' -> jni.getLongField(base, target, signature).toString()
                'F' -> jni.getFloatField(base, target, signature).toString()
                'L', '[' -> DvmSupport.formatObjectResult(jni.getObjectField(base, target, signature))
                'D' -> return McpTools.errorResult("Instance double field reads are not exposed by the Jni bridge (no getDoubleField).")
                else -> return McpTools.errorResult("Unsupported field type descriptor: $type")
            }
        } catch (e: Exception) {
            return McpTools.errorResult("Native instance field read failed (likely no native handler for this field): " +
                    (e.message ?: e.javaClass.name))
        }
        return McpTools.textResult("instance 0x" + Integer.toHexString(targetHash) + "." + field + ":" + type + " = " + rendered)
    }

    // ---------- dvm_set_field ----------

    private fun doSetField(args: JSONObject): JSONObject {
        if (emulator.isRunning()) return McpTools.errorResult("Emulator is running; stop it first.")
        val field = args.getString("field")
        if (field == null || field.trim().isEmpty()) return McpTools.errorResult("Missing required parameter 'field'.")
        val type = args.getString("type")
        if (type == null || type.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'type' (JNI descriptor).")
        }
        val value = args.getString("value")
        if (value == null) return McpTools.errorResult("Missing required parameter 'value'.")
        val base = DvmSupport.baseVm(vm)
        val targetRaw = args.getString("target_hash")
        return if (targetRaw != null && targetRaw.trim().isNotEmpty()) {
            setInstanceField(base, type, field, targetRaw, value)
        } else {
            setStaticField(args, type, field, value)
        }
    }

    /** The host instance backing a DVM object (e.g. ProxyDvmObject), or null for plumbing objects. */
    private fun hostInstance(obj: DvmObject<*>): Any? {
        val v = try { obj.getValue() } catch (e: Exception) { return null }
        return if (v == null || v is DvmObject<*>) null else v
    }

    private fun findField(start: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = start
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    private fun renderHost(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"$v\""
        is kotlin.ByteArray -> Hex.encodeHexString(v) + " (" + v.size + " bytes)"
        else -> v.toString()
    }

    /** Coerce a string value to a host field value per its JNI type descriptor. */
    private fun coerceHost(type: String, value: String): Any? = when (type[0]) {
        'Z' -> value == "true" || value == "1"
        'B' -> DvmSupport.parseLong(value).toByte()
        'S' -> DvmSupport.parseLong(value).toShort()
        'C' -> if (value.length == 1) value[0] else DvmSupport.parseLong(value).toInt().toChar()
        'I' -> DvmSupport.parseLong(value).toInt()
        'J' -> DvmSupport.parseLong(value)
        'F' -> value.toFloat()
        'D' -> value.toDouble()
        else -> value // L...; -> a String (best effort for String fields)
    }

    private fun objectArg(type: String, value: String): DvmObject<*> {
        return if (value.startsWith("@")) {
            DvmSupport.convertArg(vm, type, value) as DvmObject<*>
        } else {
            StringObject(vm, value)
        }
    }

    private fun setStaticField(args: JSONObject, type: String, field: String, value: String): JSONObject {
        val className = args.getString("class")
        if (className == null || className.trim().isEmpty()) {
            return McpTools.errorResult("For a static field write, 'class' is required (or pass 'target_hash' for an instance field).")
        }
        val cls: DvmClass = vm.resolveClass(className)
        val hash = cls.getStaticFieldID(field, type)
        if (hash == 0) {
            return McpTools.errorResult("Static field not accepted by the native JNI bridge: " + className + "->" + field + ":" + type)
        }
        val dvmField: DvmField = cls.getStaticField(hash)
                ?: return McpTools.errorResult("Could not resolve static DvmField for " + className + "->" + field + ":" + type)
        try {
            when (type[0]) {
                'Z' -> dvmField.setStaticBooleanField(value == "true" || value == "1")
                'I', 'S', 'C', 'B' -> dvmField.setStaticIntField(DvmSupport.parseLong(value).toInt())
                'J' -> dvmField.setStaticLongField(DvmSupport.parseLong(value))
                'F' -> dvmField.setStaticFloatField(value.toFloat())
                'D' -> dvmField.setStaticDoubleField(value.toDouble())
                'L', '[' -> dvmField.setStaticObjectField(objectArg(type, value))
                else -> return McpTools.errorResult("Unsupported field type descriptor: $type")
            }
        } catch (e: Exception) {
            return McpTools.errorResult("Native static field write failed (likely no native handler for this field): " +
                    (e.message ?: e.javaClass.name))
        }
        return McpTools.textResult("set static " + cls.getClassName() + "->" + field + ":" + type + " = " + value)
    }

    private fun setInstanceField(base: BaseVM, type: String, field: String, targetRaw: String, value: String): JSONObject {
        val targetHash = DvmSupport.parseHashInt(targetRaw)
                ?: return McpTools.errorResult("Invalid 'target_hash' (decimal or 0x-hex).")
        val target: DvmObject<*> = base.getObject(targetHash)
                ?: return McpTools.errorResult("No object found for target_hash 0x" + Integer.toHexString(targetHash) + ".")
        // Host-backed instance: write the actual host field reflectively (works under ProxyClassFactory).
        val host = hostInstance(target)
        if (host != null) {
            val f = findField(host.javaClass, field)
            if (f != null) {
                f.isAccessible = true
                f.set(host, coerceHost(type, value))
                return McpTools.textResult("set instance 0x" + Integer.toHexString(targetHash) + "." + field +
                        ":" + type + " = " + value + " (host)")
            }
        }
        val jni = base.jni
                ?: return McpTools.errorResult("No Jni bridge registered on the VM; cannot write instance fields.")
        val signature = field + ":" + type
        try {
            when (type[0]) {
                'Z' -> jni.setBooleanField(base, target, signature, value == "true" || value == "1")
                'I', 'S', 'C', 'B' -> jni.setIntField(base, target, signature, DvmSupport.parseLong(value).toInt())
                'J' -> jni.setLongField(base, target, signature, DvmSupport.parseLong(value))
                'F' -> jni.setFloatField(base, target, signature, value.toFloat())
                'D' -> jni.setDoubleField(base, target, signature, value.toDouble())
                'L', '[' -> jni.setObjectField(base, target, signature, objectArg(type, value))
                else -> return McpTools.errorResult("Unsupported field type descriptor: $type")
            }
        } catch (e: Exception) {
            return McpTools.errorResult("Native instance field write failed (likely no native handler for this field): " +
                    (e.message ?: e.javaClass.name))
        }
        return McpTools.textResult("set instance 0x" + Integer.toHexString(targetHash) + "." + field + ":" + type + " = " + value)
    }

    // ---------- dvm_to_string ----------

    private fun doToString(args: JSONObject): JSONObject {
        val hash = DvmSupport.parseHashInt(args.getString("hash"))
                ?: return McpTools.errorResult("Missing/invalid 'hash' (decimal or 0x-hex).")
        val base = DvmSupport.baseVm(vm)
        val obj: DvmObject<*> = base.getObject(hash)
                ?: return McpTools.errorResult("No object found for hash 0x" + Integer.toHexString(hash) + ".")
        val sb = StringBuilder()
        sb.append("object 0x").append(Integer.toHexString(hash)).append(" class=").append(DvmSupport.classNameOf(obj)).append('\n')

        // 1) try native toString() if the emulator is stopped and the class has a native handler.
        if (!emulator.isRunning()) {
            try {
                val result = obj.callJniMethodObject<DvmObject<*>>(emulator, "toString()Ljava/lang/String;")
                sb.append("native toString() = ").append(DvmSupport.formatObjectResult(result))
                return McpTools.textResult(sb.toString())
            } catch (e: Exception) {
                sb.append("(native toString() unavailable: ").append(e.message ?: e.javaClass.name)
                        .append(" — toString is rarely native-registered, falling back)\n")
            }
        } else {
            sb.append("(emulator is running; skipping native toString(), falling back)\n")
        }

        // 2) fall back to a value preview, then the host toString().
        val pv = DvmSupport.valuePreview(obj)
        if (pv != null) {
            sb.append("valuePreview = ").append(pv)
        } else {
            sb.append("hostToString = ").append(obj.toString())
        }
        return McpTools.textResult(sb.toString())
    }
}
