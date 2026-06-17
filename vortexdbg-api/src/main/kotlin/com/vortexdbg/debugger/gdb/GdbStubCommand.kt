package com.vortexdbg.debugger.gdb

import com.vortexdbg.Emulator

internal interface GdbStubCommand {

    fun processCommand(emulator: Emulator<*>, stub: GdbStub, command: String): Boolean

}
