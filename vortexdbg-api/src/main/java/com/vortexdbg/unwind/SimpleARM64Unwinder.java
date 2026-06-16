package com.vortexdbg.unwind;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.VortexdbgPointer;
import unicorn.Arm64Const;

public class SimpleARM64Unwinder extends Unwinder {

    public SimpleARM64Unwinder(Emulator<?> emulator) {
        super(emulator);
    }

    @Override
    protected String getBaseFormat() {
        return "[0x%09x]";
    }

    @Override
    public Frame createFrame(VortexdbgPointer ip, VortexdbgPointer fp) {
        if (ip != null) {
            if (ip.peer == emulator.getReturnAddress()) {
                return new Frame(ip, null);
            }

            ip = ip.share(-4, 0);
            return new Frame(ip, fp);
        } else {
            return null;
        }
    }

    private Frame initFrame(Emulator<?> emulator) {
        VortexdbgPointer ip = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_LR);
        VortexdbgPointer fp = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_FP);
        return createFrame(ip, fp);
    }

    @Override
    protected Frame unw_step(Emulator<?> emulator, Frame frame) {
        if (frame == null) {
            return initFrame(emulator);
        }

        if (frame.fp == null) {
            System.err.println("fp is null");
            return null;
        }

        VortexdbgPointer ip = frame.fp.getPointer(8);
        VortexdbgPointer fp = frame.fp.getPointer(0);
        return createFrame(ip, fp);
    }

}
