package com.vortexdbg.linux.thread;

import com.vortexdbg.Emulator;
import com.vortexdbg.unix.UnixEmulator;
import com.sun.jna.Pointer;
import unicorn.Arm64Const;
import unicorn.ArmConst;

public class FutexIndefinitelyWaiter extends FutexWaiter {

    public FutexIndefinitelyWaiter(Pointer uaddr, int val) {
        super(uaddr, val);
    }

    @Override
    protected void onContinueRunInternal(Emulator<?> emulator) {
        super.onContinueRunInternal(emulator);

        emulator.getBackend().reg_write(emulator.is32Bit() ? ArmConst.UC_ARM_REG_R0 : Arm64Const.UC_ARM64_REG_X0, -UnixEmulator.EAGAIN);
    }
}
