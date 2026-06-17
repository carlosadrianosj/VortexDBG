package com.vortexdbg.thread

import com.vortexdbg.AbstractEmulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.memory.Memory
import unicorn.Arm64Const
import unicorn.ArmConst

open class Entry(pid: Int, private val entry: Long, until: Long, private val sp: Long) : MainTask(pid, until) {

    override fun run(emulator: AbstractEmulator<*>): Number? {
        val backend = emulator.getBackend()
        val memory = emulator.getMemory()
        memory.setStackPoint(sp)
        backend.reg_write(if (emulator.is64Bit()) Arm64Const.UC_ARM64_REG_LR else ArmConst.UC_ARM_REG_LR, until)
        return emulator.emulate(entry, until)
    }

    override fun getAddress(): Long {
        return entry
    }

    override fun toThreadString(): String {
        return "Executable entry=0x" + java.lang.Long.toHexString(entry) + ", sp=0x" + java.lang.Long.toHexString(sp)
    }
}
