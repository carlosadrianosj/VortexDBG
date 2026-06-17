package com.vortexdbg.debugger.gdb

import com.vortexdbg.Emulator

internal class BreakpointCommand : GdbStubCommand {

    override fun processCommand(emulator: Emulator<*>, stub: GdbStub, command: String): Boolean {
        val divider = command.substring(3).indexOf(",")
        var address = java.lang.Long.parseLong(command.substring(3, divider + 3), 16)

        /*
         * 2: 16-bit Thumb mode breakpoint.
         * 3: 32-bit Thumb mode (Thumb-2) breakpoint.
         * 4: 32-bit ARM mode breakpoint.
         */
        val type = Integer.parseInt(command.substring(divider + 4))
        val isThumb = type == 2 || type == 3
        if (isThumb) {
            address = address or 1L
        }

        if (command.startsWith("Z0")) {
            stub.addBreakPoint(address)
        } else {
            stub.removeBreakPoint(address)
        }
        stub.makePacketAndSend("OK")
        return true
    }

}
