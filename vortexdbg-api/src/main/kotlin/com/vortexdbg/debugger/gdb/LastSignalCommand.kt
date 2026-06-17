package com.vortexdbg.debugger.gdb

import com.vortexdbg.Emulator

internal class LastSignalCommand : GdbStubCommand {

    override fun processCommand(emulator: Emulator<*>, stub: GdbStub, command: String): Boolean {
        stub.makePacketAndSend("S" + GdbStub.SIGTRAP)
        return true
    }

}
