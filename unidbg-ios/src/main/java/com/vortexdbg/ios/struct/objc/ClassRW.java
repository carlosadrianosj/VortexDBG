package com.vortexdbg.ios.struct.objc;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.UnidbgStructure;
import com.sun.jna.Pointer;

import java.util.Arrays;
import java.util.List;

public abstract class ClassRW extends UnidbgStructure implements ObjcConstants {

    ClassRW(Pointer p) {
        super(p);
    }

    public int flags;
    public int version;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("flags", "version", "ro", "methodList", "properties", "protocols", "firstSubclass", "nextSiblingClass", "demangledName");
    }

    public boolean isRealized() {
        return (flags & RW_REALIZED) != 0;
    }

    public void changeFlags(int set, int clear) {
        flags = (flags | set) & ~clear;
    }

    public abstract ClassRO ro(Emulator<?> emulator);

}
