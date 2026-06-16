package com.vortexdbg.thread

import com.vortexdbg.AbstractEmulator
import com.vortexdbg.arm.ARM
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.memory.Memory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.ArmConst

open class Function32(
    pid: Int,
    private val address: Long,
    until: Long,
    private val paddingArgument: Boolean,
    private vararg val arguments: Number
) : MainTask(pid, until) {

    override fun run(emulator: AbstractEmulator<*>): Number? {
        val backend = emulator.getBackend()
        val memory = emulator.getMemory()
        ARM.initArgs(emulator, paddingArgument, *arguments)

        val sp = memory.getStackPoint()
        if (sp % 8 != 0L) {
            log.info("SP NOT 8 bytes aligned", Exception(emulator.getStackPointer().toString()))
        }
        backend.reg_write(ArmConst.UC_ARM_REG_LR, until)
        return emulator.emulate(address, until)
    }

    override fun getAddress(): Long {
        return address
    }

    override fun toThreadString(): String {
        return "Function32 address=0x" + java.lang.Long.toHexString(address) + ", arguments=" + arguments.contentToString()
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(Function32::class.java)
    }

}
