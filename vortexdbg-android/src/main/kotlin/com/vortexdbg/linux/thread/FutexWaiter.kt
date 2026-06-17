package com.vortexdbg.linux.thread

import com.vortexdbg.Emulator
import com.sun.jna.Pointer
import unicorn.Arm64Const
import unicorn.ArmConst

abstract class FutexWaiter(private val uaddr: Pointer, private val `val`: Int) : AndroidWaiter() {

    override fun canDispatch(): Boolean {
        if (wokenUp) {
            return true
        }
        val old = uaddr.getInt(0)
        return old != `val`
    }

    final override fun onContinueRun(emulator: Emulator<*>) {
        super.onContinueRun(emulator)

        if (wokenUp) {
            emulator.getBackend().reg_write(if (emulator.is32Bit()) ArmConst.UC_ARM_REG_R0 else Arm64Const.UC_ARM64_REG_X0, 0)
        } else {
            onContinueRunInternal(emulator)
        }
    }

    protected open fun onContinueRunInternal(emulator: Emulator<*>) {
    }

    protected var wokenUp: Boolean = false

    fun wakeUp(uaddr: Pointer): Boolean {
        return if (this.uaddr == uaddr) {
            this.wokenUp = true
            true
        } else {
            false
        }
    }

}
