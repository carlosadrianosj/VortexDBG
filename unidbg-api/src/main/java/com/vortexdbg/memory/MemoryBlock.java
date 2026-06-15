package com.vortexdbg.memory;

import com.vortexdbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;

public interface MemoryBlock {

    UnidbgPointer getPointer();

    boolean isSame(Pointer pointer);

    void free();

}
