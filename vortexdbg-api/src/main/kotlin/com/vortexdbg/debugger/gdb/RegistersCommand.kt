package com.vortexdbg.debugger.gdb

import com.vortexdbg.Emulator
import com.vortexdbg.arm.ARM
import com.vortexdbg.arm.backend.Backend
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal class RegistersCommand : GdbStubCommand {

    override fun processCommand(emulator: Emulator<*>, stub: GdbStub, command: String): Boolean {
        val backend = emulator.getBackend()
        if (log.isDebugEnabled) {
            if (emulator.is32Bit()) {
                ARM.showRegs(emulator, null)
            } else {
                ARM.showRegs64(emulator, null)
            }
        }

        if (command.startsWith("g")) {
            val sb = StringBuilder()
            for (i in stub.registers.indices) {
                val value = backend.reg_read(stub.registers[i]).toLong()
                if (emulator.is32Bit()) {
                    val hex = String.format("%08x", Integer.reverseBytes((value and 0xffffffffL).toInt()))
                    sb.append(hex)
                } else {
                    val hex = String.format("%016x", java.lang.Long.reverseBytes(value))
                    sb.append(hex)
                }
            }
            stub.makePacketAndSend(sb.toString())
        } else {
            for (i in stub.registers.indices) {
                if (emulator.is32Bit()) {
                    val value = java.lang.Long.parseLong(command.substring(1 + 8 * i, 9 + 8 * i), 16)
                    backend.reg_write(stub.registers[i], Integer.reverseBytes((value and 0xffffffffL).toInt()))
                } else {
                    val value = java.lang.Long.parseLong(command.substring(1 + 16 * i, 9 + 16 * i), 16)
                    backend.reg_write(stub.registers[i], java.lang.Long.reverseBytes(value))
                }
            }
        }
        return true
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(RegistersCommand::class.java)
    }

}
