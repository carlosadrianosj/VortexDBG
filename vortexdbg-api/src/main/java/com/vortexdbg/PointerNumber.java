package com.vortexdbg;

import com.vortexdbg.pointer.VortexdbgPointer;

public class PointerNumber extends Number {

    private final VortexdbgPointer value;

    public PointerNumber(VortexdbgPointer value) {
        this.value = value;
    }

    @Override
    public int intValue() {
        return this.value == null ? 0 : (int) this.value.toUIntPeer();
    }

    @Override
    public long longValue() {
        return this.value == null ? 0L : this.value.peer;
    }

    @Override
    public float floatValue() {
        throw new AbstractMethodError();
    }

    @Override
    public double doubleValue() {
        throw new AbstractMethodError();
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
