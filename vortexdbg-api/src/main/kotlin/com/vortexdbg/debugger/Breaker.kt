package com.vortexdbg.debugger

import com.vortexdbg.pointer.VortexdbgPointer

interface Breaker {

    fun debug() {
        debug("")
    }

    fun debug(reason: String)

    fun brk(pc: VortexdbgPointer?, svcNumber: Int)

}
