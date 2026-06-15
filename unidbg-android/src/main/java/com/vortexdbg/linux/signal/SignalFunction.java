package com.vortexdbg.linux.signal;

import com.vortexdbg.AbstractEmulator;
import com.vortexdbg.Emulator;
import com.vortexdbg.arm.backend.Backend;
import com.vortexdbg.memory.MemoryBlock;
import com.vortexdbg.pointer.UnidbgPointer;
import com.vortexdbg.thread.MainTask;
import unicorn.Arm64Const;
import unicorn.ArmConst;

public class SignalFunction extends MainTask {

    private final int signum;
    private final SigAction action;

    public SignalFunction(Emulator<?> emulator, int signum, SigAction action) {
        super(emulator.getPid(), emulator.getReturnAddress());
        this.signum = signum;
        this.action = action;
    }

    @Override
    public long getAddress() {
        return action.getSaHandler();
    }

    @Override
    public String toThreadString() {
        return "SignalFunction sa_handler=" + action.getSaHandler() + ", signum=" + signum;
    }

    private MemoryBlock infoBlock;

    @Override
    public void destroy(Emulator<?> emulator) {
        super.destroy(emulator);

        if (infoBlock != null) {
            infoBlock.free();
            infoBlock = null;
        }
    }

    @Override
    protected Number run(AbstractEmulator<?> emulator) {
        Backend backend = emulator.getBackend();
        if (action.needSigInfo() && infoBlock == null) {
            infoBlock = emulator.getMemory().malloc(128, true);
            infoBlock.getPointer().setInt(0, signum);
        }
        UnidbgPointer stack = allocateStack(emulator);
        if (emulator.is32Bit()) {
            backend.reg_write(ArmConst.UC_ARM_REG_SP, stack.peer);
            backend.reg_write(ArmConst.UC_ARM_REG_R0, signum);
            backend.reg_write(ArmConst.UC_ARM_REG_R1, infoBlock == null ? 0 : infoBlock.getPointer().peer); // siginfo_t *info
            backend.reg_write(ArmConst.UC_ARM_REG_R2, 0); // void *ucontext
            backend.reg_write(ArmConst.UC_ARM_REG_LR, until);
        } else {
            backend.reg_write(Arm64Const.UC_ARM64_REG_SP, stack.peer);
            backend.reg_write(Arm64Const.UC_ARM64_REG_X0, signum);
            backend.reg_write(Arm64Const.UC_ARM64_REG_X1, infoBlock == null ? 0 : infoBlock.getPointer().peer); // siginfo_t *info
            backend.reg_write(Arm64Const.UC_ARM64_REG_X2, 0); // void *ucontext
            backend.reg_write(Arm64Const.UC_ARM64_REG_LR, until);
        }
        return emulator.emulate(action.getSaHandler(), until);
    }

}
