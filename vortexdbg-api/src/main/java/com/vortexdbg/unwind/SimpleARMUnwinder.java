package com.vortexdbg.unwind;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.ARM;
import com.vortexdbg.pointer.VortexdbgPointer;
import unicorn.ArmConst;

public class SimpleARMUnwinder extends Unwinder {

    public SimpleARMUnwinder(Emulator<?> emulator) {
        super(emulator);
    }

    @Override
    protected String getBaseFormat() {
        return "[0x%08x]";
    }

    @Override
    public Frame createFrame(VortexdbgPointer ip, VortexdbgPointer fp) {
        if (ip != null) {
            if (ip.peer == emulator.getReturnAddress()) {
                return new Frame(ip, null);
            }

            return new Frame(ARM.adjust_ip(ip), fp);
        } else {
            return null;
        }
    }

    private Frame initFrame(Emulator<?> emulator) {
        VortexdbgPointer ip = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_LR);
        VortexdbgPointer fp = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R7);
        return createFrame(ip, fp);
    }

    @Override
    protected Frame unw_step(Emulator<?> emulator, Frame frame) {
        if (frame == null) {
            return initFrame(emulator);
        }

        VortexdbgPointer sp = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_SP);
        if (frame.fp == null || frame.fp.peer < sp.peer) {
            System.err.println("fp=" + frame.fp + ", sp=" + sp);
            return null;
        }

        VortexdbgPointer ip = frame.fp.getPointer(4);
        VortexdbgPointer fp = frame.fp.getPointer(0);
        return createFrame(ip, fp);
    }

}
