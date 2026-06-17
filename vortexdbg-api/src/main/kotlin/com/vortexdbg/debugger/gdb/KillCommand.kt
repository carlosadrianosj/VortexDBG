package com.vortexdbg.debugger.gdb

import com.vortexdbg.Emulator

internal class KillCommand : GdbStubCommand {

    override fun processCommand(emulator: Emulator<*>, stub: GdbStub, command: String): Boolean {
        stub.send("+")
        stub.shutdownServer()
        System.exit(9)
        return true
    }

}
