package com.vortexdbg.arm.context;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.backend.Backend;
import com.vortexdbg.pointer.VortexdbgPointer;
import com.sun.jna.Pointer;
import unicorn.Arm64Const;

public class BackendArm64RegisterContext extends BaseRegisterContext implements EditableArm64RegisterContext {

    private final Backend backend;

    public BackendArm64RegisterContext(Backend backend, Emulator<?> emulator) {
        super(emulator, Arm64Const.UC_ARM64_REG_X0, 8);
        this.backend = backend;
    }

    private long reg(int regId) {
        return backend.reg_read(regId).longValue();
    }

    @Override
    public void setXLong(int index, long value) {
        if (index >= 0 && index <= 28) {
            backend.reg_write(Arm64Const.UC_ARM64_REG_X0 + index, value);
            return;
        }
        throw new IllegalArgumentException("invalid index: " + index);
    }

    @Override
    public long getXLong(int index) {
        if (index >= 0 && index <= 28) {
            return reg(Arm64Const.UC_ARM64_REG_X0 + index);
        }
        throw new IllegalArgumentException("invalid index: " + index);
    }

    @Override
    public int getXInt(int index) {
        return (int) getXLong(index);
    }

    @Override
    public VortexdbgPointer getXPointer(int index) {
        return VortexdbgPointer.pointer(emulator, getXLong(index));
    }

    @Override
    public long getFp() {
        return reg(Arm64Const.UC_ARM64_REG_FP);
    }

    @Override
    public VortexdbgPointer getFpPointer() {
        return VortexdbgPointer.pointer(emulator, getFp());
    }

    @Override
    public long getLR() {
        return reg(Arm64Const.UC_ARM64_REG_LR);
    }

    @Override
    public VortexdbgPointer getLRPointer() {
        return VortexdbgPointer.pointer(emulator, getLR());
    }

    @Override
    public VortexdbgPointer getPCPointer() {
        return VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_PC);
    }

    @Override
    public VortexdbgPointer getStackPointer() {
        return VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_SP);
    }

    @Override
    public void setStackPointer(Pointer sp) {
        backend.reg_write(Arm64Const.UC_ARM64_REG_SP, ((VortexdbgPointer) sp).peer);
    }
}
