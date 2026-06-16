package com.vortexdbg.linux.thread;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.VortexdbgStructure;
import com.sun.jna.Pointer;

public abstract class PThreadInternal extends VortexdbgStructure {

    public static PThreadInternal create(Emulator<?> emulator, Pointer pointer) {
        return emulator.is64Bit() ? new PThreadInternal64(pointer) : new PThreadInternal32(pointer);
    }

    public int tid;

    public PThreadInternal(Pointer p) {
        super(p);
    }

}
