package com.vortexdbg.linux.signal;

import com.vortexdbg.AbstractEmulator;
import com.vortexdbg.Emulator;
import com.vortexdbg.arm.backend.Backend;
import com.vortexdbg.memory.MemoryBlock;
import com.vortexdbg.pointer.VortexdbgPointer;
import com.vortexdbg.signal.AbstractSignalTask;
import com.vortexdbg.signal.SigSet;
import com.vortexdbg.signal.SignalOps;
import com.vortexdbg.signal.UnixSigSet;
import com.sun.jna.Pointer;
import unicorn.Arm64Const;
import unicorn.ArmConst;

public class SignalTask extends AbstractSignalTask {

    private final SigAction action;

    public SignalTask(int signum, SigAction action) {
        this(signum, action, null);
    }

    public SignalTask(int signum, SigAction action, Pointer sig_info) {
        super(signum);
        this.action = action;
        this.sig_info = sig_info;
    }

    private Pointer sig_info;
    private VortexdbgPointer stack;

    @Override
    public Number callHandler(SignalOps signalOps, AbstractEmulator<?> emulator) {
        SigSet sigSet = signalOps.getSigMaskSet();
        try {
            long sa_mask = action.getMask();
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

    private MemoryBlock ucontext;

    private Number runHandler(AbstractEmulator<?> emulator) {
        Backend backend = emulator.getBackend();
        if (stack == null) {
            stack = allocateStack(emulator);
        }
        if (action.needSigInfo() && infoBlock == null && sig_info == null) {
            infoBlock = emulator.getMemory().malloc(128, true);
            infoBlock.getPointer().setInt(0, signum);
            sig_info = infoBlock.getPointer();
        }
        if (ucontext == null) {
            ucontext = emulator.getMemory().malloc(0x1000, true);
        }
        if (emulator.is32Bit()) {
            backend.reg_write(ArmConst.UC_ARM_REG_SP, stack.peer);
            backend.reg_write(ArmConst.UC_ARM_REG_R0, signum);
            backend.reg_write(ArmConst.UC_ARM_REG_R1, VortexdbgPointer.nativeValue(sig_info)); // siginfo_t *info
            backend.reg_write(ArmConst.UC_ARM_REG_R2, VortexdbgPointer.nativeValue(ucontext.getPointer()));
            backend.reg_write(ArmConst.UC_ARM_REG_LR, emulator.getReturnAddress());
        } else {
            backend.reg_write(Arm64Const.UC_ARM64_REG_SP, stack.peer);
            backend.reg_write(Arm64Const.UC_ARM64_REG_X0, signum);
            backend.reg_write(Arm64Const.UC_ARM64_REG_X1, VortexdbgPointer.nativeValue(sig_info)); // siginfo_t *info
            backend.reg_write(Arm64Const.UC_ARM64_REG_X2, VortexdbgPointer.nativeValue(ucontext.getPointer()));
            backend.reg_write(Arm64Const.UC_ARM64_REG_LR, emulator.getReturnAddress());
        }
        return emulator.emulate(action.getSaHandler(), emulator.getReturnAddress());
    }

    @Override
    public String toThreadString() {
        return "SignalTask sa_handler=0x" + Long.toHexString(action.getSaHandler()) + ", stack=" + stack + ", signum=" + signum;
    }

    private MemoryBlock infoBlock;

    @Override
    public void destroy(Emulator<?> emulator) {
        super.destroy(emulator);

        if (ucontext != null) {
            ucontext.free();
            ucontext = null;
        }
        if (infoBlock != null) {
            infoBlock.free();
            infoBlock = null;
        }
    }

}
