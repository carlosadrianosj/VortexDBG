package com.vortexdbg.hook.hookzz;

import com.vortexdbg.arm.context.AbstractRegisterContext;
import com.vortexdbg.arm.context.RegisterContext;
import com.vortexdbg.hook.InvocationContext;
import com.vortexdbg.pointer.UnidbgPointer;

import java.util.Stack;

public abstract class HookZzRegisterContext extends AbstractRegisterContext implements RegisterContext, InvocationContext {

    private final Stack<Object> stack;

    HookZzRegisterContext(Stack<Object> stack) {
        this.stack = stack;
    }

    @Override
    public void push(Object... objs) {
        for (int i = objs.length - 1; i >= 0; i--) {
            stack.push(objs[i]);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T pop() {
        return (T) stack.pop();
    }

    @Override
    public UnidbgPointer getPCPointer() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getIntByReg(int regId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getLongByReg(int regId) {
        throw new UnsupportedOperationException();
    }
}
