package com.vortexdbg.arm.context;

import com.vortexdbg.pointer.UnidbgPointer;

public interface Arm64RegisterContext extends RegisterContext {

    long getXLong(int index);

    int getXInt(int index);

    UnidbgPointer getXPointer(int index);

    long getFp();

    UnidbgPointer getFpPointer();

}
