package com.vortexdbg.ios.struct.objc;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;

public class ObjcObject64 extends ObjcObject {

    public ObjcObject64(Emulator<?> emulator, Pointer p) {
        super(emulator, p);
    }

    public long isa;

    @Override
    public UnidbgPointer getIsa(Emulator<?> emulator) {
        return UnidbgPointer.pointer(emulator, isa);
    }

}
