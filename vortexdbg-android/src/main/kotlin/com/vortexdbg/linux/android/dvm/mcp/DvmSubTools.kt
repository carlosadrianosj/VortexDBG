package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject

/**
 * A group of DVM/Java MCP tools owned by one sub-handler. [DvmMcpTools] aggregates several of
 * these so each group can live in its own file. Build results with
 * [com.vortexdbg.mcp.McpTools.textResult] / [com.vortexdbg.mcp.McpTools.errorResult] and reuse
 * [DvmSupport] for schemas, arg marshalling and call dispatch.
 */
interface DvmSubTools {

    /** Tool schemas advertised by this group (each `{name, description, inputSchema}`). */
    fun schemas(): JSONArray

    /** Whether this group owns the given tool name. */
    fun handles(name: String): Boolean

    /** Execute the named tool. */
    fun call(name: String, args: JSONObject): JSONObject
}
