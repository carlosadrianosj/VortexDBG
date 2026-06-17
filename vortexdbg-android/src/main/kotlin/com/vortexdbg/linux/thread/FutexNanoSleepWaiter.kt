package com.vortexdbg.linux.thread

import com.vortexdbg.Emulator
import com.vortexdbg.linux.AndroidSyscallHandler
import com.vortexdbg.unix.struct.TimeSpec
import com.sun.jna.Pointer
import unicorn.Arm64Const
import unicorn.ArmConst

class FutexNanoSleepWaiter(uaddr: Pointer, `val`: Int, timeSpec: TimeSpec) : FutexWaiter(uaddr, `val`) {

    private val waitMillis: Long = timeSpec.toMillis()
    private val startWaitTimeInMillis: Long = System.currentTimeMillis()

    init {
        if (this.waitMillis <= 0) {
            throw IllegalStateException()
        }
    }

    override fun canDispatch(): Boolean {
        val ret = super.canDispatch()
        if (ret) {
            return true
        }
        if (System.currentTimeMillis() - startWaitTimeInMillis >= waitMillis) {
            return true
        }
        Thread.yield()
        return false
    }

    override fun onContinueRunInternal(emulator: Emulator<*>) {
        super.onContinueRunInternal(emulator)

        emulator.getBackend().reg_write(if (emulator.is32Bit()) ArmConst.UC_ARM_REG_R0 else Arm64Const.UC_ARM64_REG_X0, -AndroidSyscallHandler.ETIMEDOUT)
    }

}
