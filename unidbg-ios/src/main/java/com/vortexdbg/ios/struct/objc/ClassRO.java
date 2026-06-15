package com.vortexdbg.ios.struct.objc;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.UnidbgPointer;
import com.vortexdbg.pointer.UnidbgStructure;
import com.sun.jna.Pointer;

import java.util.Arrays;
import java.util.List;

/**
 * class_ro_t
 */
public abstract class ClassRO extends UnidbgStructure implements ObjcConstants {

    ClassRO(Pointer p) {
        super(p);
    }

    public int flags;
    public int instanceStart;
    public int instanceSize;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("flags", "instanceStart", "instanceSize", "ivarLayout", "name", "baseMethods", "baseProtocols", "ivars", "weakIvarLayout", "baseProperties");
    }

    public boolean isFuture() {
        return (flags & RO_FUTURE) != 0;
    }

    public boolean isMetaClass() {
        return (flags & RO_META) != 0;
    }

    public abstract UnidbgPointer getNamePointer(Emulator<?> emulator);
}
