package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.BaseVM
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmField
import com.vortexdbg.linux.android.dvm.DvmMethod
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.Jni
import com.vortexdbg.linux.android.dvm.JniInterceptor
import com.vortexdbg.linux.android.dvm.StringObject
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.linux.android.dvm.VarArg
import com.vortexdbg.linux.android.dvm.array.ByteArray as DvmByteArray
import com.vortexdbg.mcp.McpTools
import org.apache.commons.codec.binary.Hex
import java.util.ArrayDeque

/**
 * DVM/Java MCP sub-handler: native -> Java JNI interception (trace / mock / break).
 *
 * Installs a single [Jni] interceptor (the private [HookJni] class) via [BaseVM.setJni], wrapping the
 * CURRENT vm.jni as the fallback through [JniFunction] (so it composes with the spoof hook installed by
 * DvmSpoofTools, etc.). The same interceptor backs all four tools:
 *
 *  - `dvm_trace_jni`  : record every overridden native->Java call (kind/signature/args/result) into a
 *                       capped ring buffer, optionally filtered by signature substring, then delegate.
 *  - `dvm_jni_log`    : dump (and optionally clear) the recorded trace + break events.
 *  - `dvm_mock_jni`   : substitute a cooked return value for calls whose signature contains a needle
 *                       (StringObject for object returns, parsed primitive for int/long/bool), with an
 *                       optional TTL (N uses then auto-removed).
 *  - `dvm_break_on_jni`: record a "break event" (signature + args snapshot) when a matching call fires.
 *                       True thread suspension is NOT feasible in the emulate-on-call model, so the only
 *                       supported actions are `snapshot` and `log` (both just capture; execution continues).
 *
 * Like the spoof hook this only affects native->Java callbacks made during emulated calls; it does not
 * patch the host JVM. Only a handful of [Jni] methods are overridden; everything else is delegated to the
 * fallback automatically by [JniFunction].
 */
class DvmHookTools(private val emulator: Emulator<*>, private val vm: VM) : DvmSubTools {

    /** One recorded native->Java call. */
    private class TraceEntry(
            @JvmField val kind: String,
            @JvmField val signature: String,
            @JvmField val argsPreview: String,
            @JvmField val result: String)

    /** One recorded break hit. */
    private class BreakEvent(
            @JvmField val signature: String,
            @JvmField val needle: String,
            @JvmField val action: String,
            @JvmField val argsPreview: String)

    /** A registered mock: cooked value + remaining uses (-1 == unlimited). */
    private class Mock(
            @JvmField val needle: String,
            @JvmField val rawReturn: String,
            @JvmField var remaining: Int)

    /** Registered jni interceptor (null when not installed). */
    private var interceptor: JniInterceptor? = null

    /** Tracing on/off. */
    private var traceEnabled = false
    /** Trace filter (substring); null = trace everything. */
    private var traceFilter: String? = null
    /** Ring-buffer cap for trace entries. */
    private var traceMax = DEFAULT_MAX

    /** Trace ring buffer (oldest dropped when over cap). */
    private val trace = ArrayDeque<TraceEntry>()
    /** Break ring buffer. */
    private val breaks = ArrayDeque<BreakEvent>()
    /** Active mocks keyed by needle. */
    private val mocks = LinkedHashMap<String, Mock>()
    /** Active break needles -> action. */
    private val breakSigs = LinkedHashMap<String, String>()

    private val toolNames = setOf("dvm_trace_jni", "dvm_jni_log", "dvm_mock_jni", "dvm_break_on_jni")

    override fun handles(name: String): Boolean = toolNames.contains(name)

    override fun schemas(): JSONArray {
        val tools = JSONArray()
        tools.add(DvmSupport.schema("dvm_trace_jni",
                "[DVM/Java] Install/refresh a JNI interceptor (BaseVM.setJni wrapping the current jni as " +
                        "fallback via JniFunction, so it composes with the spoof hook) that records every " +
                        "overridden native->Java call (callStaticObjectMethod/callObjectMethod, the int/long/" +
                        "boolean variants, newObject, getStaticObjectField) into a capped ring buffer as " +
                        "{kind, signature, argsPreview, result}, then delegates to the real jni. Only affects " +
                        "native->Java callbacks during emulated calls; it does not patch the host JVM. " +
                        "Read the buffer with dvm_jni_log. Set enable=false to restore the original jni " +
                        "(also disables mocks/breaks since they ride the same interceptor).",
                DvmSupport.param("enable", "Optional. true (default) to install/refresh tracing, false to " +
                        "restore the original jni and stop all interception."),
                DvmSupport.param("filter", "Optional. Only record calls whose signature contains this " +
                        "substring. Null/empty = record all."),
                DvmSupport.param("max", "Optional. Ring-buffer size (oldest dropped). Default 200.")))
        tools.add(DvmSupport.schema("dvm_jni_log",
                "[DVM/Java] Return the recorded JNI trace entries and break events captured by the " +
                        "interceptor (oldest first). Set clear=true to empty the buffers afterwards.",
                DvmSupport.param("clear", "Optional. true to clear the trace + break buffers after returning them.")))
        tools.add(DvmSupport.schema("dvm_mock_jni",
                "[DVM/Java] Register a mock: when an overridden object/primitive native->Java call's " +
                        "signature CONTAINS 'signature', return a cooked value instead of delegating " +
                        "(StringObject for object returns; parsed value for int/long/boolean returns). " +
                        "Auto-installs the interceptor if needed. Optional ttl = number of uses before the " +
                        "mock auto-removes. Pass return=\"\" or remove=true to clear the mock for that needle.",
                DvmSupport.param("signature", "Substring matched against the call signature (e.g. 'getDeviceId' " +
                        "or 'Landroid/...;->isRooted()Z')."),
                DvmSupport.param("return", "Cooked return value. For object returns it becomes a Java String; " +
                        "for int/long it is parsed as a number; for boolean true/1/yes => true. Empty string clears the mock."),
                DvmSupport.param("ttl", "Optional. Number of uses before the mock auto-removes (default: unlimited)."),
                DvmSupport.param("remove", "Optional. true to remove the mock for 'signature'.")))
        tools.add(DvmSupport.schema("dvm_break_on_jni",
                "[DVM/Java] Record a break event (signature + args snapshot) whenever an overridden " +
                        "native->Java call's signature CONTAINS 'signature'. Auto-installs the interceptor. " +
                        "NOTE: true thread suspension is not feasible in the emulate-on-call model, so the " +
                        "only supported actions are 'snapshot' and 'log' (both just capture; execution " +
                        "continues). View hits via dvm_jni_log. Pass remove=true to clear the breakpoint.",
                DvmSupport.param("signature", "Substring matched against the call signature."),
                DvmSupport.param("action", "Optional. 'snapshot' (default) or 'log' — both capture a snapshot; " +
                        "neither suspends execution."),
                DvmSupport.param("remove", "Optional. true to remove the breakpoint for 'signature'.")))
        return tools
    }

    override fun call(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "dvm_trace_jni" -> traceJni(args)
                "dvm_jni_log" -> jniLog(args)
                "dvm_mock_jni" -> mockJni(args)
                "dvm_break_on_jni" -> breakOnJni(args)
                else -> McpTools.errorResult("DvmHookTools cannot handle tool: $name")
            }
        } catch (e: Exception) {
            McpTools.errorResult("$name failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------- installation ----------

    /** Ensure the interceptor is registered in vm.jniInterceptors (wraps whatever jni checkJni picks). */
    private fun ensureInstalled(): BaseVM {
        val base = DvmSupport.baseVm(vm)
        if (interceptor == null) {
            val itc = JniInterceptor { fallback -> HookJni(fallback) }
            base.jniInterceptors.add(itc)
            interceptor = itc
        }
        return base
    }

    /** Drop the interceptor (restores the original native->Java dispatch). */
    private fun uninstall(base: BaseVM) {
        interceptor?.let { base.jniInterceptors.remove(it) }
        interceptor = null
    }

    // ---------- dvm_trace_jni ----------

    private fun traceJni(args: JSONObject): JSONObject {
        // Mutating the jni hook must not race a live emulation.
        if (emulator.isRunning()) {
            return McpTools.errorResult("Emulator is running; stop it before changing the JNI hook.")
        }
        val base = DvmSupport.baseVm(vm)
        val enable = parseBool(args.get("enable"), true)

        if (!enable) {
            if (interceptor == null) {
                return McpTools.textResult("JNI interception already disabled; nothing to restore.")
            }
            uninstall(base)
            traceEnabled = false
            return McpTools.textResult("JNI interception disabled; original jni restored. " +
                    "(Trace buffer kept; mocks/breaks remain registered but inert until reinstalled.)")
        }

        val filter = args.getString("filter")
        traceFilter = if (filter == null || filter.trim().isEmpty()) null else filter
        val max = DvmSupport.parseHashInt(args.getString("max"))
        if (max != null && max > 0) {
            traceMax = max
            // Trim immediately if the new cap is smaller.
            while (trace.size > traceMax) trace.pollFirst()
        }
        traceEnabled = true
        ensureInstalled()

        val sb = StringBuilder()
        sb.append("JNI tracing enabled (interceptor installed via setJni, fallback = previous jni).\n")
        sb.append("  filter: ").append(traceFilter ?: "(all)").append('\n')
        sb.append("  max:    ").append(traceMax).append('\n')
        sb.append("  buffered entries: ").append(trace.size).append('\n')
        sb.append("Use dvm_jni_log to read recorded calls. enable=false restores the original jni.")
        return McpTools.textResult(sb.toString())
    }

    // ---------- dvm_jni_log ----------

    private fun jniLog(args: JSONObject): JSONObject {
        val clear = parseBool(args.get("clear"), false)
        val sb = StringBuilder()
        sb.append("JNI interceptor: ").append(if (interceptor != null) "installed" else "not installed")
                .append(", tracing ").append(if (traceEnabled) "ON" else "OFF")
                .append(", filter=").append(traceFilter ?: "(all)").append('\n')
        sb.append("Active mocks: ").append(mocks.size)
        if (mocks.isNotEmpty()) {
            for (m in mocks.values) {
                sb.append("\n  mock '").append(m.needle).append("' -> ").append(m.rawReturn)
                        .append(if (m.remaining < 0) " (unlimited)" else " (" + m.remaining + " use(s) left)")
            }
        }
        sb.append('\n').append("Active breakpoints: ").append(breakSigs.size)
        if (breakSigs.isNotEmpty()) {
            for ((needle, action) in breakSigs) {
                sb.append("\n  break '").append(needle).append("' action=").append(action)
            }
        }
        sb.append("\n\nTrace entries (").append(trace.size).append("):\n")
        if (trace.isEmpty()) {
            sb.append("  (none)\n")
        } else {
            var i = 0
            for (e in trace) {
                sb.append("  #").append(i++).append(" [").append(e.kind).append("] ").append(e.signature)
                        .append("\n      args: ").append(e.argsPreview)
                        .append("\n      ret:  ").append(e.result).append('\n')
            }
        }
        sb.append("\nBreak events (").append(breaks.size).append("):\n")
        if (breaks.isEmpty()) {
            sb.append("  (none)\n")
        } else {
            var i = 0
            for (b in breaks) {
                sb.append("  #").append(i++).append(" '").append(b.needle).append("' (").append(b.action)
                        .append(") hit ").append(b.signature)
                        .append("\n      args: ").append(b.argsPreview).append('\n')
            }
        }
        if (clear) {
            trace.clear()
            breaks.clear()
            sb.append("\n(buffers cleared)")
        }
        return McpTools.textResult(sb.toString())
    }

    // ---------- dvm_mock_jni ----------

    private fun mockJni(args: JSONObject): JSONObject {
        if (emulator.isRunning()) {
            return McpTools.errorResult("Emulator is running; stop it before changing JNI mocks.")
        }
        val needle = args.getString("signature")
        if (needle == null || needle.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'signature' (substring to match).")
        }
        val key = needle.trim()
        val remove = parseBool(args.get("remove"), false)
        val rawReturn = args.getString("return")
        if (remove || (rawReturn != null && rawReturn.isEmpty())) {
            val gone = mocks.remove(key) != null
            return McpTools.textResult(if (gone) "Removed mock for '$key'." else "No mock registered for '$key'.")
        }
        if (rawReturn == null) {
            return McpTools.errorResult("Missing required parameter 'return' (use return=\"\" or remove=true to clear).")
        }
        val ttlInt = DvmSupport.parseHashInt(args.getString("ttl"))
        val remaining = if (ttlInt != null && ttlInt > 0) ttlInt else -1
        mocks[key] = Mock(key, rawReturn, remaining)
        ensureInstalled()
        val sb = StringBuilder()
        sb.append("Mock registered: signature contains '").append(key).append("' -> '").append(rawReturn).append("'")
        sb.append(if (remaining < 0) " (unlimited uses)." else " (" + remaining + " use(s)).")
        sb.append("\nObject returns => Java String; int/long => parsed number; boolean => true/1/yes. ")
        sb.append("Interceptor installed; mock applies to overridden object/primitive calls during emulation.")
        return McpTools.textResult(sb.toString())
    }

    // ---------- dvm_break_on_jni ----------

    private fun breakOnJni(args: JSONObject): JSONObject {
        if (emulator.isRunning()) {
            return McpTools.errorResult("Emulator is running; stop it before changing JNI breakpoints.")
        }
        val needle = args.getString("signature")
        if (needle == null || needle.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'signature' (substring to match).")
        }
        val key = needle.trim()
        val remove = parseBool(args.get("remove"), false)
        if (remove) {
            val gone = breakSigs.remove(key) != null
            return McpTools.textResult(if (gone) "Removed breakpoint for '$key'." else "No breakpoint registered for '$key'.")
        }
        val action = (args.getString("action") ?: "snapshot").trim().lowercase()
        if (action != "snapshot" && action != "log") {
            return McpTools.errorResult("Unsupported action '$action'. Only 'snapshot' or 'log' are supported " +
                    "(true thread suspension is not feasible in the emulate-on-call model).")
        }
        breakSigs[key] = action
        ensureInstalled()
        return McpTools.textResult("Breakpoint registered: signature contains '" + key + "' action=" + action +
                ".\nNOTE: execution is NOT suspended; each hit records a {signature, args snapshot} break event " +
                "viewable via dvm_jni_log. Interceptor installed.")
    }

    // ---------- helpers ----------

    private fun parseBool(raw: Any?, default: Boolean): Boolean {
        if (raw == null) return default
        if (raw is Boolean) return raw
        return when (raw.toString().trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> default
        }
    }

    /** Best-effort args preview from a VarArg without throwing. */
    private fun previewArgs(varArg: VarArg?): String {
        if (varArg == null) return "(no args)"
        return try {
            varArg.formatArgs()
        } catch (e: Exception) {
            try {
                varArg.args.toString()
            } catch (e2: Exception) {
                "(args unavailable)"
            }
        }
    }

    /** Record one trace entry honoring the filter and ring-buffer cap. */
    private fun record(kind: String, signature: String, argsPreview: String, result: String) {
        if (!traceEnabled) return
        val f = traceFilter
        if (f != null && !signature.contains(f)) return
        trace.addLast(TraceEntry(kind, signature, argsPreview, result))
        while (trace.size > traceMax) trace.pollFirst()
    }

    /** Record a break hit if any break needle matches the signature. */
    private fun maybeBreak(signature: String, argsPreview: String) {
        if (breakSigs.isEmpty()) return
        for ((needle, action) in breakSigs) {
            if (signature.contains(needle)) {
                breaks.addLast(BreakEvent(signature, needle, action, argsPreview))
                while (breaks.size > traceMax) breaks.pollFirst()
            }
        }
    }

    /** Find a live mock whose needle matches the signature, consuming a TTL use; null = no mock. */
    private fun matchMock(signature: String): Mock? {
        if (mocks.isEmpty()) return null
        val it = mocks.values.iterator()
        while (it.hasNext()) {
            val m = it.next()
            if (signature.contains(m.needle)) {
                if (m.remaining > 0) {
                    m.remaining -= 1
                    if (m.remaining == 0) it.remove()
                }
                return m
            }
        }
        return null
    }

    private fun parseMockLong(raw: String): Long = try {
        DvmSupport.parseLong(raw)
    } catch (e: Exception) {
        0L
    }

    private fun parseMockInt(raw: String): Int = try {
        DvmSupport.parseLong(raw).toInt()
    } catch (e: Exception) {
        0
    }

    private fun parseMockBool(raw: String): Boolean = when (raw.trim().lowercase()) {
        "true", "1", "yes" -> true
        else -> false
    }

    /** Cook a mocked object/array return based on the method's return descriptor (e.g. [B vs String). */
    private fun cookObjectReturn(vm: BaseVM, signature: String, raw: String): DvmObject<*> {
        val ret = signature.substringAfterLast(')')
        return if (ret == "[B") DvmByteArray(vm, Hex.decodeHex(raw.toCharArray())) else StringObject(vm, raw)
    }

    // ---------- the interceptor ----------

    /**
     * Wraps the selected [Jni] via Kotlin interface delegation (`by fallback`) so EVERY un-overridden
     * Jni method delegates to the real jni with the DvmMethod intact (critical for ProxyJni, which only
     * implements the DvmMethod-typed overloads). We override the DvmMethod/DvmField-typed methods we care
     * about for trace/mock/break and delegate the rest to [fallback].
     */
    private inner class HookJni(private val fallback: Jni) : Jni by fallback {

        override fun callStaticObjectMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> {
            val sig = dvmMethod.getSignature()
            val ap = previewArgs(varArg)
            maybeBreak(sig, ap)
            matchMock(sig)?.let { m ->
                record("callStaticObjectMethod(mock)", sig, ap, "\"" + m.rawReturn + "\"")
                return cookObjectReturn(vm, sig, m.rawReturn)
            }
            val res = fallback.callStaticObjectMethod(vm, dvmClass, dvmMethod, varArg)
            record("callStaticObjectMethod", sig, ap, DvmSupport.valuePreview(res) ?: DvmSupport.classNameOf(res))
            return res
        }

        override fun callObjectMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> {
            val sig = dvmMethod.getSignature()
            val ap = previewArgs(varArg)
            maybeBreak(sig, ap)
            matchMock(sig)?.let { m ->
                record("callObjectMethod(mock)", sig, ap, "\"" + m.rawReturn + "\"")
                return cookObjectReturn(vm, sig, m.rawReturn)
            }
            val res = fallback.callObjectMethod(vm, dvmObject, dvmMethod, varArg)
            record("callObjectMethod", sig, ap, DvmSupport.valuePreview(res) ?: DvmSupport.classNameOf(res))
            return res
        }

        override fun newObject(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> {
            val sig = dvmMethod.getSignature()
            val ap = previewArgs(varArg)
            maybeBreak(sig, ap)
            val res = fallback.newObject(vm, dvmClass, dvmMethod, varArg)
            record("newObject", sig, ap, DvmSupport.valuePreview(res) ?: DvmSupport.classNameOf(res))
            return res
        }

        override fun getStaticObjectField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): DvmObject<*> {
            val sig = dvmField.getSignature()
            maybeBreak(sig, "(field)")
            matchMock(sig)?.let { m ->
                record("getStaticObjectField(mock)", sig, "(field)", "\"" + m.rawReturn + "\"")
                return cookObjectReturn(vm, sig, m.rawReturn)
            }
            val res = fallback.getStaticObjectField(vm, dvmClass, dvmField)
            record("getStaticObjectField", sig, "(field)", DvmSupport.valuePreview(res) ?: DvmSupport.classNameOf(res))
            return res
        }

        override fun callStaticIntMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Int {
            val sig = dvmMethod.getSignature()
            val ap = previewArgs(varArg)
            maybeBreak(sig, ap)
            matchMock(sig)?.let { m ->
                val v = parseMockInt(m.rawReturn)
                record("callStaticIntMethod(mock)", sig, ap, v.toString())
                return v
            }
            val res = fallback.callStaticIntMethod(vm, dvmClass, dvmMethod, varArg)
            record("callStaticIntMethod", sig, ap, res.toString())
            return res
        }

        override fun callIntMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Int {
            val sig = dvmMethod.getSignature()
            val ap = previewArgs(varArg)
            maybeBreak(sig, ap)
            matchMock(sig)?.let { m ->
                val v = parseMockInt(m.rawReturn)
                record("callIntMethod(mock)", sig, ap, v.toString())
                return v
            }
            val res = fallback.callIntMethod(vm, dvmObject, dvmMethod, varArg)
            record("callIntMethod", sig, ap, res.toString())
            return res
        }

        override fun callStaticLongMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Long {
            val sig = dvmMethod.getSignature()
            val ap = previewArgs(varArg)
            maybeBreak(sig, ap)
            matchMock(sig)?.let { m ->
                val v = parseMockLong(m.rawReturn)
                record("callStaticLongMethod(mock)", sig, ap, v.toString())
                return v
            }
            val res = fallback.callStaticLongMethod(vm, dvmClass, dvmMethod, varArg)
            record("callStaticLongMethod", sig, ap, res.toString())
            return res
        }

        override fun callLongMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Long {
            val sig = dvmMethod.getSignature()
            val ap = previewArgs(varArg)
            maybeBreak(sig, ap)
            matchMock(sig)?.let { m ->
                val v = parseMockLong(m.rawReturn)
                record("callLongMethod(mock)", sig, ap, v.toString())
                return v
            }
            val res = fallback.callLongMethod(vm, dvmObject, dvmMethod, varArg)
            record("callLongMethod", sig, ap, res.toString())
            return res
        }

        override fun callStaticBooleanMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Boolean {
            val sig = dvmMethod.getSignature()
            val ap = previewArgs(varArg)
            maybeBreak(sig, ap)
            matchMock(sig)?.let { m ->
                val v = parseMockBool(m.rawReturn)
                record("callStaticBooleanMethod(mock)", sig, ap, v.toString())
                return v
            }
            val res = fallback.callStaticBooleanMethod(vm, dvmClass, dvmMethod, varArg)
            record("callStaticBooleanMethod", sig, ap, res.toString())
            return res
        }

        override fun callBooleanMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Boolean {
            val sig = dvmMethod.getSignature()
            val ap = previewArgs(varArg)
            maybeBreak(sig, ap)
            matchMock(sig)?.let { m ->
                val v = parseMockBool(m.rawReturn)
                record("callBooleanMethod(mock)", sig, ap, v.toString())
                return v
            }
            val res = fallback.callBooleanMethod(vm, dvmObject, dvmMethod, varArg)
            record("callBooleanMethod", sig, ap, res.toString())
            return res
        }
    }

    companion object {
        private const val DEFAULT_MAX = 200
    }
}
