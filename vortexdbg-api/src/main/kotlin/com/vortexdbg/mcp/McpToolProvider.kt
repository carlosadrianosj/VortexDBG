package com.vortexdbg.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject

/**
 * Extension point for MCP tools that live OUTSIDE vortexdbg-api (for example the
 * Dalvik/DEX tools in vortexdbg-android). vortexdbg-api does not depend on the
 * android module, so DVM/Java tools cannot live in [McpTools] directly; instead they
 * implement this interface and are registered with the running server.
 *
 * A provider advertises its own tool schemas in tools/list and handles tools/call for
 * the names it owns. Register before starting the MCP server (typing `mcp` in the
 * console) via [com.vortexdbg.debugger.Debugger.addMcpToolProvider].
 *
 * Provider tools are dispatched as NON-execution tools: they run on the debugger thread
 * while the emulator is in debug-idle state. That makes it safe to re-enter the emulator
 * from a provider (e.g. to call a Java method on the host VM through the JNI bridge).
 */
interface McpToolProvider {

    /** Tool schemas to advertise in tools/list. Each entry is `{name, description, inputSchema}`. */
    fun schemas(): JSONArray

    /** Whether this provider owns the given tool name. */
    fun handles(name: String): Boolean

    /** Execute the named tool. Build results with [McpTools.textResult] / [McpTools.errorResult]. */
    fun call(name: String, args: JSONObject): JSONObject
}
