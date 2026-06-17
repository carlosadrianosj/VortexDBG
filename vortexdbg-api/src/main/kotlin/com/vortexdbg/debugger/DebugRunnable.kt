package com.vortexdbg.debugger

interface DebugRunnable<T> {

    @Throws(Exception::class)
    fun runWithArgs(args: Array<String>?): T

}
