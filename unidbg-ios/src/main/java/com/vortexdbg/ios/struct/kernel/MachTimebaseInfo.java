package com.vortexdbg.ios.struct.kernel;

import com.vortexdbg.pointer.UnidbgStructure;
import com.sun.jna.Pointer;

import java.util.Arrays;
import java.util.List;

public class MachTimebaseInfo extends UnidbgStructure {

    public MachTimebaseInfo(Pointer p) {
        super(p);
    }

    public int numer;
    public int denom;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("numer", "denom");
    }
}
