package com.vortexdbg.debugger.gdb

import com.vortexdbg.Emulator

internal class StepCommand : GdbStubCommand {

    override fun processCommand(emulator: Emulator<*>, stub: GdbStub, command: String): Boolean {
        stub.singleStep()
        stub.makePacketAndSend("OK")
        return true
    }

}
