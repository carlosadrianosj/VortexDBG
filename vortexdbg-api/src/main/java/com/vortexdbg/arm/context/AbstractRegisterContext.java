package com.vortexdbg.arm.context;

import com.vortexdbg.pointer.VortexdbgPointer;

public abstract class AbstractRegisterContext implements RegisterContext {

    @Override
    public final int getIntArg(int index) {
        return (int) getLongArg(index);
    }

    @Override
    public final long getLongArg(int index) {
        VortexdbgPointer pointer = getPointerArg(index);
        return pointer == null ? 0 : pointer.peer;
    }

}
