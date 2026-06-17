package com.vortexdbg.debugger

import com.vortexdbg.Emulator
import com.vortexdbg.arm.CodeHistory

interface DebugListener {

    fun canDebug(emulator: Emulator<*>, currentCode: CodeHistory): Boolean

}
