package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.mcp.McpTools

/**
 * DVM/Java MCP sub-handler: record / replay of DVM tool sequences (B12, dvm_call_phase).
 *
 * While recording, [DvmMcpTools] feeds each tool call (except dvm_call_phase itself) into
 * [record]. Replay re-dispatches the recorded (name,args) through [replay], which [DvmMcpTools]
 * wires to its own `call`. Handles are content-derived (vm.hash / identity), so a replay only
 * reproduces deterministically if the same objects get the same hashes (documented caveat).
 */
class DvmPhaseTools(private val emulator: Emulator<*>, private val vm: VM) : DvmSubTools {

    /** Set by DvmMcpTools to re-dispatch recorded calls during replay. */
    var replay: ((String, JSONObject) -> JSONObject)? = null

    private class Step(@JvmField val tool: String, @JvmField val args: JSONObject)

    private val phases: MutableMap<String, MutableList<Step>> = LinkedHashMap()
    private var recordingName: String? = null

    fun isRecording(): Boolean = recordingName != null

    fun record(name: String, args: JSONObject) {
        val rec = recordingName ?: return
        phases[rec]?.add(Step(name, JSONObject(LinkedHashMap(args))))
    }

    override fun handles(name: String): Boolean = name == "dvm_call_phase"

    override fun schemas(): JSONArray {
        val tools = JSONArray()
        tools.add(DvmSupport.schema("dvm_call_phase",
                "[DVM/Java] Record a sequence of DVM tool calls and replay it later. " +
                        "action=start begins recording every subsequent dvm_* call into a named phase; " +
                        "action=stop ends it; action=replay re-runs a phase's calls in order; action=list " +
                        "shows stored phases. Handles are content/identity-derived, so replay is deterministic " +
                        "only when the same objects yield the same hashes.",
                DvmSupport.param("action", "One of: start | stop | replay | list."),
                DvmSupport.param("name", "Phase name (required for start/replay; the active phase for stop).")))
        return tools
    }

    override fun call(name: String, args: JSONObject): JSONObject {
        if (name != "dvm_call_phase") return McpTools.errorResult("DvmPhaseTools cannot handle tool: $name")
        return try {
            when (args.getString("action")?.trim()?.lowercase()) {
                "start" -> start(args)
                "stop" -> stop()
                "replay" -> replayPhase(args)
                "list" -> list()
                else -> McpTools.errorResult("Missing/invalid 'action' (start|stop|replay|list).")
            }
        } catch (e: Exception) {
            McpTools.errorResult("dvm_call_phase failed: " + (e.message ?: e.javaClass.name))
        }
    }

    private fun start(args: JSONObject): JSONObject {
        val n = args.getString("name") ?: return McpTools.errorResult("Missing 'name'.")
        phases[n] = ArrayList()
        recordingName = n
        return McpTools.textResult("Recording phase '$n' started; subsequent dvm_* calls are captured. Use action=stop to finish.")
    }

    private fun stop(): JSONObject {
        val n = recordingName ?: return McpTools.textResult("No phase is recording.")
        recordingName = null
        return McpTools.textResult("Recording phase '$n' stopped (" + (phases[n]?.size ?: 0) + " step(s) captured).")
    }

    private fun replayPhase(args: JSONObject): JSONObject {
        val n = args.getString("name") ?: return McpTools.errorResult("Missing 'name'.")
        val steps = phases[n] ?: return McpTools.errorResult("No such phase: '$n'.")
        val dispatch = replay ?: return McpTools.errorResult("Replay not wired.")
        val wasRecording = recordingName
        recordingName = null // never record during replay
        val sb = StringBuilder()
        sb.append("Replaying phase '").append(n).append("' (").append(steps.size).append(" step(s)):\n")
        try {
            var i = 1
            for (s in steps) {
                val result = dispatch(s.tool, s.args)
                sb.append("  ").append(i++).append(". ").append(s.tool).append(" -> ")
                        .append(extractText(result)).append('\n')
            }
        } finally {
            recordingName = wasRecording
        }
        return McpTools.textResult(sb.toString().trimEnd())
    }

    private fun list(): JSONObject {
        if (phases.isEmpty()) return McpTools.textResult("No phases recorded.")
        val sb = StringBuilder("Phases:\n")
        for ((k, v) in phases) {
            sb.append("  ").append(k).append(": ").append(v.size).append(" step(s)")
            if (k == recordingName) sb.append(" (recording)")
            sb.append('\n')
        }
        return McpTools.textResult(sb.toString().trimEnd())
    }

    private fun extractText(result: JSONObject): String {
        return try {
            val content = result.getJSONArray("content")
            val first = content.getJSONObject(0)
            val t = first.getString("text") ?: ""
            val oneLine = t.replace('\n', ' ').trim()
            if (oneLine.length > 120) oneLine.substring(0, 120) + "..." else oneLine
        } catch (e: Exception) {
            "(ok)"
        }
    }
}
