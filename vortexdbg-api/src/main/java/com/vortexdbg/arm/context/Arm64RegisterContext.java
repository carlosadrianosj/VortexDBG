package com.vortexdbg.arm.context;

import com.vortexdbg.pointer.VortexdbgPointer;

public interface Arm64RegisterContext extends RegisterContext {

    long getXLong(int index);

    int getXInt(int index);

    VortexdbgPointer getXPointer(int index);

    long getFp();

    VortexdbgPointer getFpPointer();

}
