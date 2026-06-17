package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.BaseVM
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.StringObject
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.mcp.McpToolProvider
import com.vortexdbg.mcp.McpTools
import org.apache.commons.codec.binary.Hex
import com.vortexdbg.linux.android.dvm.array.ByteArray as DvmByteArray

/**
 * MCP tools for the Dalvik/Java (DEX) side of Vortex-DBG, the host-JVM half of the A1
 * fusion. While the built-in tools in vortexdbg-api drive the emulated ARM (native) side,
 * these let an AI client inspect the live Dalvik VM and CALL Java methods on the host JVM
 * through the JNI bridge.
 *
 * Register before starting the MCP server:
 * ```
 * Debugger debugger = emulator.attach();
 * debugger.addMcpToolProvider(new DvmMcpTools(emulator, vm));
 * ```
 *
 * Tools: dvm_list_classes, dvm_list_objects, dvm_read_string, dvm_get_object, dvm_call_static.
 */
class DvmMcpTools(private val emulator: Emulator<*>, private val vm: VM) : McpToolProvider {

    private val names = setOf(
            "dvm_list_classes", "dvm_list_objects", "dvm_read_string",
            "dvm_get_object", "dvm_call_static")

    override fun handles(name: String): Boolean = names.contains(name)

    override fun schemas(): JSONArray {
        val tools = JSONArray()
        tools.add(schema("dvm_list_classes",
                "[DVM/Java] List the Dalvik classes currently resolved on the host JVM (the app's DEX/Java classes that Vortex runs)."))
        tools.add(schema("dvm_list_objects",
                "[DVM/Java] List live JNI object references (local + global) held by the VM: hash handle, class and a value preview."))
        tools.add(schema("dvm_read_string",
                "[DVM/Java] Read the Java String value of a StringObject by its JNI hash handle (from dvm_list_objects).",
                param("hash", "Decimal or 0x-hex JNI handle of the object.")))
        tools.add(schema("dvm_get_object",
                "[DVM/Java] Inspect a DVM object by JNI hash handle: class name and value preview.",
                param("hash", "Decimal or 0x-hex JNI handle.")))
        tools.add(schema("dvm_call_static",
                "[DVM/Java] Call a STATIC Java method on the host VM through JNI and return its result. " +
                        "Return and argument types are taken from the JNI signature. Arguments are converted per the " +
                        "signature: Ljava/lang/String; -> plain text, I/S/B/C -> integer, J -> long, Z -> true/false, " +
                        "[B -> hex bytes. Requires the emulator stopped (not running).",
                param("class", "Class name in slash form, e.g. com/example/aeskeychain/SecureVault"),
                param("method", "JNI method signature, e.g. seal(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                param("args", "Optional. JSON array of string arguments matching the signature.", "array")))
        return tools
    }

    override fun call(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "dvm_list_classes" -> listClasses()
                "dvm_list_objects" -> listObjects()
                "dvm_read_string" -> readString(args)
                "dvm_get_object" -> getObject(args)
                "dvm_call_static" -> callStatic(args)
                else -> McpTools.errorResult("Unknown DVM tool: $name")
            }
        } catch (e: Exception) {
            McpTools.errorResult("DVM tool '$name' failed: " + (e.message ?: e.javaClass.name))
        }
    }

    private fun baseVm(): BaseVM {
        return vm as? BaseVM ?: throw IllegalStateException("VM is not a BaseVM, cannot inspect DVM state")
    }

    private fun listClasses(): JSONObject {
        val classes = baseVm().classMap.values
        val sb = StringBuilder()
        sb.append(classes.size).append(" resolved DVM classes:\n")
        for (c in classes) {
            sb.append("  ").append(c.getClassName()).append('\n')
        }
        return McpTools.textResult(sb.toString())
    }

    private fun listObjects(): JSONObject {
        val base = baseVm()
        val sb = StringBuilder()
        sb.append("local refs (").append(base.localObjectMap.size).append("):\n")
        for ((hash, ref) in base.localObjectMap) {
            appendObject(sb, hash, ref.obj)
        }
        sb.append("global refs (").append(base.globalObjectMap.size).append("):\n")
        for ((hash, ref) in base.globalObjectMap) {
            appendObject(sb, hash, ref.obj)
        }
        return McpTools.textResult(sb.toString())
    }

    private fun appendObject(sb: StringBuilder, hash: Int, obj: DvmObject<*>) {
        val cls = try {
            obj.getObjectType()?.getClassName() ?: obj.javaClass.simpleName
        } catch (e: Exception) {
            obj.javaClass.simpleName
        }
        sb.append("  0x").append(Integer.toHexString(hash)).append(" (").append(hash).append(") ").append(cls)
        val preview = valuePreview(obj)
        if (preview != null) sb.append(" = ").append(preview)
        sb.append('\n')
    }

    private fun readString(args: JSONObject): JSONObject {
        val hash = parseHash(args) ?: return McpTools.errorResult("Missing required parameter 'hash'.")
        val obj = baseVm().getObject<DvmObject<*>>(hash)
        val value = obj.getValue()
        if (value is String) {
            return McpTools.textResult(value)
        }
        return McpTools.errorResult("Object 0x" + Integer.toHexString(hash) + " is not a String (it is " +
                (value?.javaClass?.name ?: "null") + ").")
    }

    private fun getObject(args: JSONObject): JSONObject {
        val hash = parseHash(args) ?: return McpTools.errorResult("Missing required parameter 'hash'.")
        val obj = baseVm().getObject<DvmObject<*>>(hash)
        val cls = obj.getObjectType()?.getClassName() ?: obj.javaClass.name
        val sb = StringBuilder()
        sb.append("0x").append(Integer.toHexString(hash)).append(" : ").append(cls).append('\n')
        val preview = valuePreview(obj)
        if (preview != null) sb.append("value = ").append(preview).append('\n')
        return McpTools.textResult(sb.toString())
    }

    private fun callStatic(args: JSONObject): JSONObject {
        if (emulator.isRunning()) {
            return McpTools.errorResult("Cannot call a Java method while the emulator is running.")
        }
        val className = args.getString("class")
                ?: return McpTools.errorResult("Missing required parameter 'class' (slash form, e.g. com/example/Foo).")
        val method = args.getString("method")
                ?: return McpTools.errorResult("Missing required parameter 'method' (JNI signature).")
        val open = method.indexOf('(')
        val close = method.indexOf(')')
        if (open < 0 || close < 0 || close < open) {
            return McpTools.errorResult("Invalid method signature: $method")
        }
        val argTypes = parseArgTypes(method.substring(open + 1, close))
        val retDescriptor = method.substring(close + 1)
        if (retDescriptor.isEmpty()) {
            return McpTools.errorResult("Invalid method signature (no return type): $method")
        }
        val argArray = args.getJSONArray("args")
        val provided = argArray?.size ?: 0
        if (provided != argTypes.size) {
            return McpTools.errorResult("Signature expects " + argTypes.size + " args but got " + provided + ".")
        }
        val callArgs = arrayOfNulls<Any?>(argTypes.size)
        for (i in argTypes.indices) {
            callArgs[i] = convertArg(argTypes[i], argArray!!.getString(i))
        }
        val cls: DvmClass = vm.resolveClass(className)
        val rendered = when (retDescriptor[0]) {
            'V' -> { cls.callStaticJniMethod(emulator, method, *callArgs); "void" }
            'Z' -> cls.callStaticJniMethodBoolean(emulator, method, *callArgs).toString()
            'B', 'C', 'S', 'I' -> cls.callStaticJniMethodInt(emulator, method, *callArgs).toString()
            'J' -> cls.callStaticJniMethodLong(emulator, method, *callArgs).toString()
            'L', '[' -> formatObjectResult(cls.callStaticJniMethodObject<DvmObject<*>>(emulator, method, *callArgs))
            else -> return McpTools.errorResult("Unsupported return type: $retDescriptor")
        }
        return McpTools.textResult(className + "." + method.substring(0, open) + " -> " + rendered)
    }

    private fun valuePreview(obj: DvmObject<*>): String? {
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

    private fun formatObjectResult(obj: DvmObject<*>?): String {
        if (obj == null) return "null"
        val value = obj.getValue() ?: return "null"
        return when (value) {
            is String -> "\"$value\""
            is kotlin.ByteArray -> Hex.encodeHexString(value) + " (" + value.size + " bytes)"
            else -> value.toString()
        }
    }

    private fun convertArg(type: String, raw: String): Any? {
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

    private fun parseArgTypes(descriptor: String): List<String> {
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

    private fun parseLong(raw: String): Long {
        val s = raw.trim()
        return if (s.startsWith("0x") || s.startsWith("0X")) {
            java.lang.Long.parseLong(s.substring(2), 16)
        } else {
            java.lang.Long.parseLong(s)
        }
    }

    private fun parseHash(args: JSONObject): Int? {
        val raw = args.getString("hash") ?: return null
        val s = raw.trim()
        return if (s.startsWith("0x") || s.startsWith("0X")) {
            Integer.parseUnsignedInt(s.substring(2), 16)
        } else {
            Integer.parseInt(s)
        }
    }

    private fun schema(name: String, description: String, vararg params: JSONObject): JSONObject {
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

    private fun param(name: String, description: String, type: String = "string"): JSONObject {
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
}
