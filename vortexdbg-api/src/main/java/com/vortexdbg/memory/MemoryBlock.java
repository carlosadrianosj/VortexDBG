package com.vortexdbg.memory;

import com.vortexdbg.pointer.VortexdbgPointer;
import com.sun.jna.Pointer;

public interface MemoryBlock {

    VortexdbgPointer getPointer();

    boolean isSame(Pointer pointer);

    void free();

}
