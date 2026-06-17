package com.vortexdbg.debugger.ida

import com.vortexdbg.Emulator

abstract class DebuggerEvent {

    abstract fun pack(emulator: Emulator<*>): ByteArray

}
