package com.vortexdbg.debugger.gdb

import com.vortexdbg.Emulator

internal class ExtendedCommand : GdbStubCommand {

    override fun processCommand(emulator: Emulator<*>, stub: GdbStub, command: String): Boolean {
        if ("vCont?" == command) {
            stub.makePacketAndSend("vCont;c;s")
            return true
        }
        if ("vCont;c" == command) {
            stub.resumeRun()
            return true
        }
        if ("vCont;s" == command) {
            stub.singleStep()
            return true
        }
        return false
    }

}
