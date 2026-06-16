package com.vortexdbg.thread

import com.vortexdbg.AbstractEmulator
import com.vortexdbg.arm.ARM
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.memory.Memory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const

open class Function64(
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
        if (sp % 16 != 0L) {
            log.info("SP NOT 16 bytes aligned", Exception(emulator.getStackPointer().toString()))
        }
        backend.reg_write(Arm64Const.UC_ARM64_REG_LR, until)
        return emulator.emulate(address, until)
    }

    override fun getAddress(): Long {
        return address
    }

    override fun toThreadString(): String {
        return "Function64 address=0x" + java.lang.Long.toHexString(address) + ", arguments=" + arguments.contentToString()
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(Function64::class.java)
    }

}
