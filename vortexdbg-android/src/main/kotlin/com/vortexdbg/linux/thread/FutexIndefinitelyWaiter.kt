package com.vortexdbg.linux.thread

import com.vortexdbg.Emulator
import com.vortexdbg.unix.UnixEmulator
import com.sun.jna.Pointer
import unicorn.Arm64Const
import unicorn.ArmConst

class FutexIndefinitelyWaiter(uaddr: Pointer, `val`: Int) : FutexWaiter(uaddr, `val`) {

    override fun onContinueRunInternal(emulator: Emulator<*>) {
        super.onContinueRunInternal(emulator)

        emulator.getBackend().reg_write(if (emulator.is32Bit()) ArmConst.UC_ARM_REG_R0 else Arm64Const.UC_ARM64_REG_X0, -UnixEmulator.EAGAIN)
    }
}
