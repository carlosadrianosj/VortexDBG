package com.vortexdbg.hook;

import com.vortexdbg.arm.context.EditableArm64RegisterContext;
import com.vortexdbg.pointer.VortexdbgPointer;
import com.sun.jna.Pointer;

import java.util.Stack;

class Arm64HookContext extends HookContext implements EditableArm64RegisterContext {

    private final EditableArm64RegisterContext registerContext;

    Arm64HookContext(Stack<Object> stack, EditableArm64RegisterContext registerContext) {
        super(stack);
        this.registerContext = registerContext;
    }

    @Override
    public void setXLong(int index, long value) {
        registerContext.setXLong(index, value);
    }

    @Override
    public void setStackPointer(Pointer sp) {
        registerContext.setStackPointer(sp);
    }

    @Override
    public long getXLong(int index) {
        return registerContext.getXLong(index);
    }

    @Override
    public int getXInt(int index) {
        return registerContext.getXInt(index);
    }

    @Override
    public VortexdbgPointer getXPointer(int index) {
        return registerContext.getXPointer(index);
    }

    @Override
    public long getFp() {
        return registerContext.getFp();
    }

    @Override
    public VortexdbgPointer getFpPointer() {
        return registerContext.getFpPointer();
    }

    @Override
    public int getIntArg(int index) {
        return registerContext.getIntArg(index);
    }

    @Override
    public long getLongArg(int index) {
        return registerContext.getLongArg(index);
    }

    @Override
    public VortexdbgPointer getPointerArg(int index) {
        return registerContext.getPointerArg(index);
    }

    @Override
    public long getLR() {
        return registerContext.getLR();
    }

    @Override
    public VortexdbgPointer getLRPointer() {
        return registerContext.getLRPointer();
    }

    @Override
    public VortexdbgPointer getPCPointer() {
        return registerContext.getPCPointer();
    }

    @Override
    public VortexdbgPointer getStackPointer() {
        return registerContext.getStackPointer();
    }

    @Override
    public int getIntByReg(int regId) {
        return registerContext.getIntByReg(regId);
    }

    @Override
    public long getLongByReg(int regId) {
        return registerContext.getLongByReg(regId);
    }
}
