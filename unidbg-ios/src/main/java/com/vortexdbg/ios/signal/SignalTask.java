package com.vortexdbg.ios.signal;

import com.vortexdbg.AbstractEmulator;
import com.vortexdbg.Emulator;
import com.vortexdbg.arm.backend.Backend;
import com.vortexdbg.memory.MemoryBlock;
import com.vortexdbg.pointer.UnidbgPointer;
import com.vortexdbg.signal.AbstractSignalTask;
import com.vortexdbg.signal.SigSet;
import com.vortexdbg.signal.SignalOps;
import com.vortexdbg.signal.UnixSigSet;
import unicorn.Arm64Const;
import unicorn.ArmConst;

public class SignalTask extends AbstractSignalTask {

    private final SigAction action;

    public SignalTask(int signum, SigAction action) {
        super(signum);
        this.action = action;
    }

    private UnidbgPointer stack;

    @Override
    public Number callHandler(SignalOps signalOps, AbstractEmulator<?> emulator) {
        SigSet sigSet = signalOps.getSigMaskSet();
        try {
            long sa_mask = action.sa_mask;
            if (sigSet == null) {
                SigSet newSigSet = new UnixSigSet(sa_mask);
                signalOps.setSigMaskSet(newSigSet);
            } else {
                sigSet.blockSigSet(sa_mask);
            }
            if (isContextSaved()) {
                return continueRun(emulator, emulator.getReturnAddress());
            }
            return runHandler(emulator);
        } finally {
            signalOps.setSigMaskSet(sigSet);
        }
    }

    private Number runHandler(AbstractEmulator<?> emulator) {
        Backend backend = emulator.getBackend();
        if (stack == null) {
            stack = allocateStack(emulator);
        }
        if (action.needSigInfo() && infoBlock == null) {
            infoBlock = emulator.getMemory().malloc(128, true);
            infoBlock.getPointer().setInt(0, signum);
        }
        if (emulator.is32Bit()) {
            backend.reg_write(ArmConst.UC_ARM_REG_SP, stack.peer);
            backend.reg_write(ArmConst.UC_ARM_REG_R0, signum);
            backend.reg_write(ArmConst.UC_ARM_REG_R1, infoBlock == null ? 0 : infoBlock.getPointer().peer); // siginfo_t *info
            backend.reg_write(ArmConst.UC_ARM_REG_R2, 0); // void *ucontext
            backend.reg_write(ArmConst.UC_ARM_REG_LR, emulator.getReturnAddress());
        } else {
            backend.reg_write(Arm64Const.UC_ARM64_REG_SP, stack.peer);
            backend.reg_write(Arm64Const.UC_ARM64_REG_X0, signum);
            backend.reg_write(Arm64Const.UC_ARM64_REG_X1, infoBlock == null ? 0 : infoBlock.getPointer().peer); // siginfo_t *info
            backend.reg_write(Arm64Const.UC_ARM64_REG_X2, 0); // void *ucontext
            backend.reg_write(Arm64Const.UC_ARM64_REG_LR, emulator.getReturnAddress());
        }
        return emulator.emulate(action.getSaHandler(), emulator.getReturnAddress());
    }

    @Override
    public String toThreadString() {
        return "SignalTask sa_handler=" + action.getSaHandler() + ", stack=" + stack + ", signum=" + signum;
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

}
