package com.vortexdbg.debugger

import java.util.Arrays
import java.util.LinkedHashMap

open class McpToolkit : DebugRunnable<Void?> {

    private val tools: MutableMap<String, McpTool> = LinkedHashMap()
    private var defaultToolName: String? = null

    open fun addTool(tool: McpTool): McpToolkit {
        if (defaultToolName == null) {
            defaultToolName = tool.name()
        }
        tools[tool.name()] = tool
        return this
    }

    open fun setDefaultTool(name: String) {
        if (!tools.containsKey(name)) {
            throw IllegalArgumentException("Tool not found: $name")
        }
        this.defaultToolName = name
    }

    @Throws(Exception::class)
    open fun run(debugger: Debugger) {
        for (tool in tools.values) {
            debugger.addMcpTool(tool.name(), tool.description(), *tool.paramNames())
        }
        debugger.run(this)
    }

    @Throws(Exception::class)
    override fun runWithArgs(args: Array<String>?): Void? {
        var toolName: String? = if (args != null) args[0] else null
        if (toolName == null) {
            toolName = defaultToolName
        }
        val tool: McpTool? = if (toolName != null) tools[toolName] else null
        if (tool != null) {
            val params: Array<String> = if (args != null && args.size > 1) Arrays.copyOfRange(args, 1, args.size) else arrayOf()
            tool.execute(params)
        }
        return null
    }

}
