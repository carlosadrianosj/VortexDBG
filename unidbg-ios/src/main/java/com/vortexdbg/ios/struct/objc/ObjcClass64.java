package com.vortexdbg.ios.struct.objc;

import com.vortexdbg.Emulator;
import com.vortexdbg.ios.objc.processor.ObjcMethod;
import com.vortexdbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;

public class ObjcClass64 extends ObjcClass {

    public long isa;
    public long superClass;
    public long cache;
    public long vtable;
    public long data;

    public ObjcClass64(Emulator<?> emulator, Pointer p) {
        super(emulator, p);
    }

    @Override
    public UnidbgPointer getIsa(Emulator<?> emulator) {
        return UnidbgPointer.pointer(emulator, isa);
    }

    @Override
    protected UnidbgPointer getDataPointer(Emulator<?> emulator) {
        return UnidbgPointer.pointer(emulator, data);
    }

    @Override
    public ObjcMethod[] getMethods() {
        throw new UnsupportedOperationException();
    }
}
