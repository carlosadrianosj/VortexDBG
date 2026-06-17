package com.vortexdbg.linux.android

interface LogCatHandler {

    fun handleLog(type: String, level: LogCatLevel?, tag: String, text: String)

}
