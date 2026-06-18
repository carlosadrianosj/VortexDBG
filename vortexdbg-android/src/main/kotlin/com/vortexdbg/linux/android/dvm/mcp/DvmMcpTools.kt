package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.mcp.McpToolProvider
import com.vortexdbg.mcp.McpTools

/**
 * MCP tools for the Dalvik/Java (DEX) side of Vortex-DBG, the host-JVM half of the A1 fusion.
 * While the built-in tools in vortexdbg-api drive the emulated ARM (native) side, these let an AI
 * client inspect the live Dalvik VM and CALL Java methods on the host JVM through the JNI bridge.
 *
 * Register before starting the MCP server:
 * ```
 * Debugger debugger = emulator.attach();
 * debugger.addMcpToolProvider(new DvmMcpTools(emulator, vm));
 * ```
 *
 * Core tools live here (dvm_list_classes, dvm_list_objects, dvm_read_string, dvm_get_object,
 * dvm_call_static); further groups are delegated to [DvmSubTools] sub-handlers (bridge, discovery,
 * state, spoof).
 */
class DvmMcpTools(private val emulator: Emulator<*>, private val vm: VM) : McpToolProvider {

    private val phase = DvmPhaseTools(emulator, vm)

    private val subs: List<DvmSubTools> = listOf(
            DvmBridgeTools(emulator, vm),
            DvmDiscoveryTools(emulator, vm),
            DvmStateTools(emulator, vm),
            DvmSpoofTools(emulator, vm),
            DvmIntrospectTools(emulator, vm),
            DvmReflectTools(emulator, vm),
            DvmFieldTools(emulator, vm),
            DvmObjectTools(emulator, vm),
            DvmHookTools(emulator, vm),
            DvmDexTools(emulator, vm),
            phase)

    init {
        phase.replay = { n, a -> call(n, a) }
    }

    private val coreNames = setOf(
            "dvm_list_classes", "dvm_list_objects", "dvm_read_string",
            "dvm_get_object", "dvm_call_static")

    override fun handles(name: String): Boolean =
            coreNames.contains(name) || subs.any { it.handles(name) }

    override fun schemas(): JSONArray {
        val tools = JSONArray()
        tools.add(DvmSupport.schema("dvm_list_classes",
                "[DVM/Java] List the Dalvik classes currently resolved on the host JVM (the app's DEX/Java classes that Vortex runs)."))
        tools.add(DvmSupport.schema("dvm_list_objects",
                "[DVM/Java] List live JNI object references (local + global) held by the VM: hash handle, class and a value preview."))
        tools.add(DvmSupport.schema("dvm_read_string",
                "[DVM/Java] Read the Java String value of a StringObject by its JNI hash handle (from dvm_list_objects).",
                DvmSupport.param("hash", "Decimal or 0x-hex JNI handle of the object.")))
        tools.add(DvmSupport.schema("dvm_get_object",
                "[DVM/Java] Inspect a DVM object by JNI hash handle: class name and value preview.",
                DvmSupport.param("hash", "Decimal or 0x-hex JNI handle.")))
        tools.add(DvmSupport.schema("dvm_call_static",
                "[DVM/Java] Call a STATIC Java method on the host VM through JNI and return its result. " +
                        "Return and argument types are taken from the JNI signature. Arguments are converted per the " +
                        "signature: Ljava/lang/String; -> plain text, I/S/B/C -> integer, J -> long, Z -> true/false, " +
                        "[B -> hex bytes, and object/array slots may be passed as @0x-handle. Requires the emulator stopped.",
                DvmSupport.param("class", "Class name in slash form, e.g. com/example/aeskeychain/SecureVault"),
                DvmSupport.param("method", "JNI method signature, e.g. seal(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                DvmSupport.param("args", "Optional. JSON array of string arguments matching the signature.", "array")))
        for (s in subs) {
            tools.addAll(s.schemas())
        }
        return tools
    }

    override fun call(name: String, args: JSONObject): JSONObject {
        if (name != "dvm_call_phase" && phase.isRecording()) {
            phase.record(name, args)
        }
        return try {
            when (name) {
                "dvm_list_classes" -> listClasses()
                "dvm_list_objects" -> listObjects()
                "dvm_read_string" -> readString(args)
                "dvm_get_object" -> getObject(args)
                "dvm_call_static" -> callStatic(args)
                else -> {
                    for (s in subs) {
                        if (s.handles(name)) return s.call(name, args)
                    }
                    McpTools.errorResult("Unknown DVM tool: $name")
                }
            }
        } catch (e: Exception) {
            McpTools.errorResult("DVM tool '$name' failed: " + (e.message ?: e.javaClass.name))
        }
    }

    private fun listClasses(): JSONObject {
        val classes = DvmSupport.baseVm(vm).classMap.values
        val sb = StringBuilder()
        sb.append(classes.size).append(" resolved DVM classes:\n")
        for (c in classes) {
            sb.append("  ").append(c.getClassName()).append('\n')
        }
        return McpTools.textResult(sb.toString())
    }

    private fun listObjects(): JSONObject {
        val base = DvmSupport.baseVm(vm)
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
        sb.append("  0x").append(Integer.toHexString(hash)).append(" (").append(hash).append(") ")
                .append(DvmSupport.classNameOf(obj))
        val preview = DvmSupport.valuePreview(obj)
        if (preview != null) sb.append(" = ").append(preview)
        sb.append('\n')
    }

    private fun readString(args: JSONObject): JSONObject {
        val hash = DvmSupport.parseHashInt(args.getString("hash"))
                ?: return McpTools.errorResult("Missing required parameter 'hash'.")
        val obj = DvmSupport.baseVm(vm).getObject<DvmObject<*>>(hash)
        val value = obj.getValue()
        if (value is String) {
            return McpTools.textResult(value)
        }
        return McpTools.errorResult("Object 0x" + Integer.toHexString(hash) + " is not a String (it is " +
                (value?.javaClass?.name ?: "null") + ").")
    }

    private fun getObject(args: JSONObject): JSONObject {
        val hash = DvmSupport.parseHashInt(args.getString("hash"))
                ?: return McpTools.errorResult("Missing required parameter 'hash'.")
        val obj = DvmSupport.baseVm(vm).getObject<DvmObject<*>>(hash)
        val cls = obj.getObjectType()?.getClassName() ?: obj.javaClass.name
        val sb = StringBuilder()
        sb.append("0x").append(Integer.toHexString(hash)).append(" : ").append(cls).append('\n')
        val preview = DvmSupport.valuePreview(obj)
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
        val result = DvmSupport.callStatic(emulator, vm, className, method, args.getJSONArray("args"))
        return McpTools.textResult(className + "." + method.substring(0, open) + " -> " + result.rendered)
    }
}
