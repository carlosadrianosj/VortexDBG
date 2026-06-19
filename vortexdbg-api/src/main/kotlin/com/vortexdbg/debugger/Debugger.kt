package com.vortexdbg.debugger

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.arm.backend.BlockHook
import com.vortexdbg.arm.backend.DebugHook

interface Debugger : Breaker, DebugHook, BlockHook {

    fun addBreakPoint(module: Module?, symbol: String): BreakPoint
    fun addBreakPoint(module: Module?, symbol: String, callback: BreakPointCallback): BreakPoint
    fun addBreakPoint(module: Module?, offset: Long): BreakPoint
    fun addBreakPoint(module: Module?, offset: Long, callback: BreakPointCallback): BreakPoint

    /**
     * @param address an odd address denotes a Thumb-mode breakpoint
     */
    fun addBreakPoint(address: Long): BreakPoint
    fun addBreakPoint(address: Long, callback: BreakPointCallback?): BreakPoint

    fun traceFunctionCall(listener: FunctionCallListener)

    /**
     * use with unicorn
     * @param module `null` means all modules.
     */
    fun traceFunctionCall(module: Module?, listener: FunctionCallListener)

    @Suppress("unused")
    fun setDebugListener(listener: DebugListener)

    @Throws(Exception::class)
    fun <T> run(runnable: DebugRunnable<T>?): T

    fun hasRunnable(): Boolean

    fun isDebugging(): Boolean

    fun disassembleBlock(emulator: Emulator<*>, address: Long, thumb: Boolean)

    fun addMcpTool(name: String, description: String, vararg paramNames: String)

    /** Register an out-of-module MCP tool provider (e.g. the Dalvik/DEX tools). Applied when the MCP server starts. */
    fun addMcpToolProvider(provider: com.vortexdbg.mcp.McpToolProvider)

    fun removeBreakPoint(address: Long): Boolean

    fun getBreakPoints(): MutableMap<Long, BreakPoint>

    fun close()

}
