package com.vortexdbg.debugger.gdb

import com.vortexdbg.Emulator
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal class SetThreadCommand : GdbStubCommand {

    override fun processCommand(emulator: Emulator<*>, stub: GdbStub, command: String): Boolean {
        val type = command[1]
        val thread = Integer.parseInt(command.substring(2), 16)
        if (log.isDebugEnabled) {
            log.debug("Set thread type={}, thread={}", type, thread)
        }
        when (type) {
            'c', 'g' -> stub.makePacketAndSend("OK")
            else -> stub.makePacketAndSend("E22")
        }
        return true
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(SetThreadCommand::class.java)
    }

}
