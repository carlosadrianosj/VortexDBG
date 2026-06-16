package com.vortexdbg.unix.struct;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.VortexdbgStructure;
import com.sun.jna.Pointer;

import java.nio.charset.StandardCharsets;

public abstract class StdString extends VortexdbgStructure {

    public static StdString createStdString(Emulator<?> emulator, Pointer pointer) {
        if (emulator.is64Bit()) {
            return new StdString64(pointer);
        } else {
            return new StdString32(pointer);
        }
    }

    StdString(Pointer p) {
        super(p);
    }

    public final String getValue(Emulator<?> emulator) {
        return new String(getData(emulator), StandardCharsets.UTF_8);
    }

    public final byte[] getData(Emulator<?> emulator) {
        return getDataPointer(emulator).getByteArray(0, (int) getDataSize());
    }

    public abstract Pointer getDataPointer(Emulator<?> emulator);
    public abstract long getDataSize();

}
