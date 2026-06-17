package com.vortexdbg.debugger.gdb

import com.vortexdbg.Emulator

internal class EnableExtendedModeCommand : GdbStubCommand {

    override fun processCommand(emulator: Emulator<*>, stub: GdbStub, command: String): Boolean {
        stub.makePacketAndSend("OK")
        return true
    }

}
