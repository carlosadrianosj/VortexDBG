package com.vortexdbg.hook.hookzz;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.VortexdbgPointer;
import com.sun.jna.Pointer;
import unicorn.Arm64Const;

import java.util.Stack;

public class HookZzArm64RegisterContextImpl extends HookZzRegisterContext implements HookZzArm64RegisterContext {

    private final Pointer reg_ctx;
    private final Emulator<?> emulator;

    HookZzArm64RegisterContextImpl(Emulator<?> emulator, Stack<Object> context) {
        super(context);
        this.reg_ctx = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_X0).share(8); // skip dummy
        this.emulator = emulator;
    }

    @Override
    public VortexdbgPointer getPointerArg(int index) {
        if (index < 8) {
            return getXPointer(index);
        }

        VortexdbgPointer sp = getStackPointer();
        return sp.getPointer((long) (index - 8) * emulator.getPointerSize());
    }

    @Override
    public long getXLong(int index) {
        if (index >= 0 && index <= 28) {
            return reg_ctx.getLong(index * 8);
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
        return reg_ctx.getLong(29 * 8);
    }

    @Override
    public void setXLong(int index, long value) {
        if (index >= 0 && index <= 28) {
            reg_ctx.setLong(index * 8, value);
        } else {
            throw new IllegalArgumentException("invalid index: " + index);
        }
    }

    @Override
    public void setStackPointer(Pointer sp) {
        throw new UnsupportedOperationException();
    }

    @Override
    public VortexdbgPointer getFpPointer() {
        return VortexdbgPointer.pointer(emulator, getFp());
    }

    @Override
    public long getLR() {
        return reg_ctx.getLong(30 * 8);
    }

    @Override
    public VortexdbgPointer getLRPointer() {
        return VortexdbgPointer.pointer(emulator, getLR());
    }

    @Override
    public VortexdbgPointer getStackPointer() {
        return (VortexdbgPointer) reg_ctx.share(30 * 8 + 8 + 16 * 8);
    }
}
