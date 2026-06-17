package com.vortexdbg.debugger

interface McpTool {

    fun name(): String

    fun description(): String

    fun paramNames(): Array<String> {
        return arrayOf()
    }

    @Throws(Exception::class)
    fun execute(params: Array<String>)

}
