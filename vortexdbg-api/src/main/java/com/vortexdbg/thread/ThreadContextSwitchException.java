package com.vortexdbg.thread;

import com.vortexdbg.Emulator;
import com.vortexdbg.LongJumpException;
import com.vortexdbg.arm.backend.Backend;
import unicorn.Arm64Const;
import unicorn.ArmConst;

public class ThreadContextSwitchException extends LongJumpException {

    private boolean setReturnValue;
    private long returnValue;

    public ThreadContextSwitchException setReturnValue(long returnValue) {
        this.setReturnValue = true;
        this.returnValue = returnValue;
        return this;
    }

    private boolean setErrno;
    private int errno;

    public ThreadContextSwitchException setErrno(int errno) {
        this.setErrno = true;
        this.errno = errno;
        return this;
    }

    public void syncReturnValue(Emulator<?> emulator) {
        if (setReturnValue) {
            Backend backend = emulator.getBackend();
            backend.reg_write(emulator.is32Bit() ? ArmConst.UC_ARM_REG_R0 : Arm64Const.UC_ARM64_REG_X0, returnValue);
        }
        if (setErrno) {
            emulator.getMemory().setErrno(errno);
        }
    }

}
