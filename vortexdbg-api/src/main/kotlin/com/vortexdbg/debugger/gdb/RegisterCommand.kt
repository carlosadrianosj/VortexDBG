package com.vortexdbg.debugger.gdb

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import unicorn.ArmConst

internal class RegisterCommand : GdbStubCommand {

    override fun processCommand(emulator: Emulator<*>, stub: GdbStub, command: String): Boolean {
        val backend = emulator.getBackend()
        val reg: Int
        if (command.startsWith("p")) {
            reg = Integer.parseInt(command.substring(1), 16)
            val `val` = readRegister(backend, stub, reg)
            if (emulator.is32Bit()) {
                stub.makePacketAndSend(String.format("%08x", Integer.reverseBytes((`val` and 0xffffffffL).toInt())))
            } else {
                stub.makePacketAndSend(String.format("%016x", java.lang.Long.reverseBytes(`val`)))
            }
        } else {
            reg = Integer.parseInt(command.substring(1, command.indexOf('=')), 16)
            val `val` = java.lang.Long.parseLong(command.substring(command.indexOf('=') + 1), 16)
            writeRegister(emulator, stub, reg, `val`)
            stub.makePacketAndSend("OK")
        }
        return true
    }

    private fun readRegister(backend: Backend, stub: GdbStub, reg: Int): Long {
        val index: Int
        if (reg >= 0 && reg < stub.registers.size) {
            index = stub.registers[reg]
        } else if (reg == 0x18) { // for arm32
            index = ArmConst.UC_ARM_REG_FP
        } else if (reg == 0x19) { // for arm32
            index = ArmConst.UC_ARM_REG_CPSR
        } else {
            index = -1
        }

        return if (index != -1) {
            backend.reg_read(index).toLong()
        } else {
            0
        }
    }

    private fun writeRegister(emulator: Emulator<*>, stub: GdbStub, reg: Int, `val`: Long) {
        val backend = emulator.getBackend()
        if (reg >= 0 && reg < stub.registers.size) {
            if (emulator.is32Bit()) {
                backend.reg_write(stub.registers[reg], (`val` and 0xffffffffL).toInt())
            } else {
                backend.reg_write(stub.registers[reg], `val`)
            }
        } else if (reg == 0x19) { // for arm32
            backend.reg_write(ArmConst.UC_ARM_REG_CPSR, Integer.reverseBytes((`val` and 0xffffffffL).toInt()))
        }
    }

}
