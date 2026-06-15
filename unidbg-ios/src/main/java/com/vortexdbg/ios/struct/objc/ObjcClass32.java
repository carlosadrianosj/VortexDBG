package com.vortexdbg.ios.struct.objc;

import com.vortexdbg.Emulator;
import com.vortexdbg.ios.objc.processor.ObjcMethod;
import com.vortexdbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;

public class ObjcClass32 extends ObjcClass {

    public int isa;
    public int superClass;
    public int cache;
    public int vtable;
    public int data;

    public ObjcClass32(Emulator<?> emulator, Pointer p) {
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
