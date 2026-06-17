package com.vortexdbg.debugger.gdb

import com.vortexdbg.Emulator

internal class DetachCommand : GdbStubCommand {

    override fun processCommand(emulator: Emulator<*>, stub: GdbStub, command: String): Boolean {
        stub.makePacketAndSend("OK")
        stub.detachServer()
        return true
    }

}
