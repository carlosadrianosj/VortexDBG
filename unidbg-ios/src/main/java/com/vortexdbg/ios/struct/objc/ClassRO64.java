package com.vortexdbg.ios.struct.objc;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;

public class ClassRO64 extends ClassRO {

    public long ivarLayout;
    public long name;
    public long baseMethods;
    public long baseProtocols;
    public long ivars;
    public long weakIvarLayout;
    public long baseProperties;

    public ClassRO64(Pointer p) {
        super(p);
    }

    @Override
    public UnidbgPointer getNamePointer(Emulator<?> emulator) {
        return UnidbgPointer.pointer(emulator, name);
    }
}
