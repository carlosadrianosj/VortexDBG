package com.vortexdbg.linux.thread

import com.vortexdbg.Emulator
import com.vortexdbg.signal.SignalTask
import com.vortexdbg.unix.UnixEmulator
import com.vortexdbg.unix.struct.TimeSpec
import com.sun.jna.Pointer
import unicorn.Arm64Const
import unicorn.ArmConst

class NanoSleepWaiter(private val emulator: Emulator<*>, private val rem: Pointer?, timeSpec: TimeSpec) : AndroidWaiter() {

    private val waitMillis: Long = timeSpec.toMillis()
    private val startWaitTimeInMillis: Long = System.currentTimeMillis()

    init {
        if (this.waitMillis <= 0) {
            throw IllegalStateException()
        }
    }

    private var onSignal = false

    override fun onSignal(task: SignalTask) {
        super.onSignal(task)

        onSignal = true

        if (rem != null) {
            val timeSpec = TimeSpec.createTimeSpec(emulator, rem)
            val elapsed = System.currentTimeMillis() - startWaitTimeInMillis
            timeSpec!!.setMillis(waitMillis - elapsed)
        }
    }

    override fun onContinueRun(emulator: Emulator<*>) {
        super.onContinueRun(emulator)

        if (onSignal) {
            emulator.getBackend().reg_write(if (emulator.is32Bit()) ArmConst.UC_ARM_REG_R0 else Arm64Const.UC_ARM64_REG_X0, -UnixEmulator.EINTR)
        }
    }

    override fun canDispatch(): Boolean {
        if (onSignal) {
            return true
        }
        if (System.currentTimeMillis() - startWaitTimeInMillis >= waitMillis) {
            return true
        }
        Thread.yield()
        return false
    }

}
