package com.vortexdbg.arm.context;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.backend.Backend;
import com.vortexdbg.pointer.VortexdbgPointer;

public abstract class BaseRegisterContext extends AbstractRegisterContext implements RegisterContext {

    protected final Emulator<?> emulator;
    private final int firstArgReg;
    private final int regArgCount;

    BaseRegisterContext(Emulator<?> emulator, int firstArgReg, int regArgCount) {
        this.emulator = emulator;
        this.firstArgReg = firstArgReg;
        this.regArgCount = regArgCount;
    }

    @Override
    public VortexdbgPointer getPointerArg(int index) {
        if (index < regArgCount) {
            int reg = firstArgReg + index;
            return VortexdbgPointer.register(emulator, reg);
        }

        VortexdbgPointer sp = getStackPointer();
        return sp.getPointer((long) (index - regArgCount) * emulator.getPointerSize());
    }

    @Override
    public int getIntByReg(int regId) {
        Backend backend = emulator.getBackend();
        Number number = backend.reg_read(regId);
        return number.intValue();
    }

    @Override
    public long getLongByReg(int regId) {
        Backend backend = emulator.getBackend();
        Number number = backend.reg_read(regId);
        return number.longValue();
    }

}
