package com.vortexdbg.hook.hookzz;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.context.RegisterContext;
import com.vortexdbg.pointer.VortexdbgPointer;
import com.sun.jna.Pointer;
import unicorn.ArmConst;

import java.util.Stack;

public class HookZzArm32RegisterContextImpl extends HookZzRegisterContext implements RegisterContext, HookZzArm32RegisterContext {

    private final Pointer reg_ctx;
    private final Emulator<?> emulator;

    HookZzArm32RegisterContextImpl(Emulator<?> emulator, Stack<Object> context) {
        super(context);
        this.reg_ctx = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R0).share(8); // skip dummy
        this.emulator = emulator;
    }

    @Override
    public VortexdbgPointer getPointerArg(int index) {
        if (index < 4) {
            switch (index) {
                case 0:
                    return getR0Pointer();
                case 1:
                    return getR1Pointer();
                case 2:
                    return getR2Pointer();
                case 3:
                    return getR3Pointer();
                default:
                    throw new IllegalArgumentException("index=" + index);
            }
        }

        VortexdbgPointer sp = getStackPointer();
        return sp.getPointer((long) (index - 4) * emulator.getPointerSize());
    }

    @Override
    public void setR0(int r0) {
        reg_ctx.setInt(0, r0);
    }

    @Override
    public void setR1(int r1) {
        reg_ctx.setInt(4, r1);
    }

    @Override
    public void setR2(int r2) {
        reg_ctx.setInt(8, r2);
    }

    @Override
    public void setR3(int r3) {
        reg_ctx.setInt(12, r3);
    }

    @Override
    public void setR4(int r4) {
        reg_ctx.setInt(16, r4);
    }

    @Override
    public void setR5(int r5) {
        reg_ctx.setInt(20, r5);
    }

    @Override
    public void setR6(int r6) {
        reg_ctx.setInt(24, r6);
    }

    @Override
    public void setR7(int r7) {
        reg_ctx.setInt(28, r7);
    }

    @Override
    public void setStackPointer(Pointer sp) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getR0Long() {
        return reg_ctx.getInt(0) & 0xffffffffL;
    }

    @Override
    public long getR1Long() {
        return reg_ctx.getInt(4) & 0xffffffffL;
    }

    @Override
    public long getR2Long() {
        return reg_ctx.getInt(8) & 0xffffffffL;
    }

    @Override
    public long getR3Long() {
        return reg_ctx.getInt(12) & 0xffffffffL;
    }

    @Override
    public long getR4Long() {
        return reg_ctx.getInt(16) & 0xffffffffL;
    }

    @Override
    public long getR5Long() {
        return reg_ctx.getInt(20) & 0xffffffffL;
    }

    @Override
    public long getR6Long() {
        return reg_ctx.getInt(24) & 0xffffffffL;
    }

    @Override
    public long getR7Long() {
        return reg_ctx.getInt(28) & 0xffffffffL;
    }

    @Override
    public long getR8Long() {
        return reg_ctx.getInt(32) & 0xffffffffL;
    }

    @Override
    public long getR9Long() {
        return reg_ctx.getInt(36) & 0xffffffffL;
    }

    @Override
    public long getR10Long() {
        return reg_ctx.getInt(40) & 0xffffffffL;
    }

    @Override
    public long getR11Long() {
        return reg_ctx.getInt(44) & 0xffffffffL;
    }

    @Override
    public long getR12Long() {
        return reg_ctx.getInt(48) & 0xffffffffL;
    }

    @Override
    public long getLR() {
        return reg_ctx.getInt(52) & 0xffffffffL;
    }

    @Override
    public int getR0Int() {
        return (int) getR0Long();
    }

    @Override
    public int getR1Int() {
        return (int) getR1Long();
    }

    @Override
    public int getR2Int() {
        return (int) getR2Long();
    }

    @Override
    public int getR3Int() {
        return (int) getR3Long();
    }

    @Override
    public int getR4Int() {
        return (int) getR4Long();
    }

    @Override
    public int getR5Int() {
        return (int) getR5Long();
    }

    @Override
    public int getR6Int() {
        return (int) getR6Long();
    }

    @Override
    public int getR7Int() {
        return (int) getR7Long();
    }

    @Override
    public int getR8Int() {
        return (int) getR8Long();
    }

    @Override
    public int getR9Int() {
        return (int) getR9Long();
    }

    @Override
    public int getR10Int() {
        return (int) getR10Long();
    }

    @Override
    public int getR11Int() {
        return (int) getR11Long();
    }

    @Override
    public int getR12Int() {
        return (int) getR12Long();
    }

    @Override
    public VortexdbgPointer getStackPointer() {
        return (VortexdbgPointer) reg_ctx.share(56);
    }

    @Override
    public VortexdbgPointer getR0Pointer() {
        return VortexdbgPointer.pointer(emulator, getR0Long());
    }

    @Override
    public VortexdbgPointer getR1Pointer() {
        return VortexdbgPointer.pointer(emulator, getR1Long());
    }

    @Override
    public VortexdbgPointer getR2Pointer() {
        return VortexdbgPointer.pointer(emulator, getR2Long());
    }

    @Override
    public VortexdbgPointer getR3Pointer() {
        return VortexdbgPointer.pointer(emulator, getR3Long());
    }

    @Override
    public VortexdbgPointer getR4Pointer() {
        return VortexdbgPointer.pointer(emulator, getR4Long());
    }

    @Override
    public VortexdbgPointer getR5Pointer() {
        return VortexdbgPointer.pointer(emulator, getR5Long());
    }

    @Override
    public VortexdbgPointer getR6Pointer() {
        return VortexdbgPointer.pointer(emulator, getR6Long());
    }

    @Override
    public VortexdbgPointer getR7Pointer() {
        return VortexdbgPointer.pointer(emulator, getR7Long());
    }

    @Override
    public VortexdbgPointer getR8Pointer() {
        return VortexdbgPointer.pointer(emulator, getR8Long());
    }

    @Override
    public VortexdbgPointer getR9Pointer() {
        return VortexdbgPointer.pointer(emulator, getR9Long());
    }

    @Override
    public VortexdbgPointer getR10Pointer() {
        return VortexdbgPointer.pointer(emulator, getR10Long());
    }

    @Override
    public VortexdbgPointer getR11Pointer() {
        return VortexdbgPointer.pointer(emulator, getR11Long());
    }

    @Override
    public VortexdbgPointer getR12Pointer() {
        return VortexdbgPointer.pointer(emulator, getR12Long());
    }

    @Override
    public VortexdbgPointer getLRPointer() {
        return VortexdbgPointer.pointer(emulator, getLR());
    }
}
