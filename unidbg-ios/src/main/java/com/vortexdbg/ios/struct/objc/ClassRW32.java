package com.vortexdbg.ios.struct.objc;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;

public class ClassRW32 extends ClassRW {

    public int ro;
    public int methodList;
    public int properties;
    public int protocols;

    public int firstSubclass;
    public int nextSiblingClass;

    public int demangledName;

    public ClassRW32(Pointer p) {
        super(p);
    }

    @Override
    public ClassRO ro(Emulator<?> emulator) {
        ClassRO ro = new ClassRO32(UnidbgPointer.pointer(emulator, this.ro));
        ro.unpack();
        return ro;
    }
}
