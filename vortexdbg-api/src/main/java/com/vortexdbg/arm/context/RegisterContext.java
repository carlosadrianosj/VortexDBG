package com.vortexdbg.arm.context;

import com.vortexdbg.pointer.VortexdbgPointer;

public interface RegisterContext {

    /**
     * @param index 0 based
     */
    int getIntArg(int index);

    /**
     * @param index 0 based
     */
    long getLongArg(int index);

    /**
     * @param index 0 based
     */
    VortexdbgPointer getPointerArg(int index);

    long getLR();

    VortexdbgPointer getLRPointer();

    VortexdbgPointer getPCPointer();

    /**
     * sp
     */
    VortexdbgPointer getStackPointer();

    int getIntByReg(int regId);
    long getLongByReg(int regId);

}
