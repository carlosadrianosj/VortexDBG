package com.vortexdbg.debugger

interface BreakPoint {

    fun isTemporary(): Boolean
    fun setTemporary(temporary: Boolean)
    fun getCallback(): BreakPointCallback?
    fun isThumb(): Boolean

}
