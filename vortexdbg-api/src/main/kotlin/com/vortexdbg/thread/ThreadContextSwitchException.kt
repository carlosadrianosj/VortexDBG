package com.vortexdbg.thread

import com.vortexdbg.Emulator
import com.vortexdbg.LongJumpException
import com.vortexdbg.arm.backend.Backend
import unicorn.Arm64Const
import unicorn.ArmConst

open class ThreadContextSwitchException : LongJumpException() {

    private var setReturnValue = false
    private var returnValue: Long = 0

    open fun setReturnValue(returnValue: Long): ThreadContextSwitchException {
        this.setReturnValue = true
        this.returnValue = returnValue
        return this
    }

    private var setErrno = false
    private var errno = 0

    open fun setErrno(errno: Int): ThreadContextSwitchException {
        this.setErrno = true
        this.errno = errno
        return this
    }

    open fun syncReturnValue(emulator: Emulator<*>) {
        if (setReturnValue) {
            val backend = emulator.getBackend()
            backend.reg_write(if (emulator.is32Bit()) ArmConst.UC_ARM_REG_R0 else Arm64Const.UC_ARM64_REG_X0, returnValue)
        }
        if (setErrno) {
            emulator.getMemory().setErrno(errno)
        }
    }

}
