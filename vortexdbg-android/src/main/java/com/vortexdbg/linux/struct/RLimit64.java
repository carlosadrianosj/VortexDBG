package com.vortexdbg.linux.struct;

import com.vortexdbg.pointer.VortexdbgStructure;
import com.sun.jna.Pointer;

import java.util.Arrays;
import java.util.List;

public class RLimit64 extends VortexdbgStructure {

    public long rlim_cur;
    public long rlim_max;

    public RLimit64(Pointer p) {
        super(p);
    }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("rlim_cur", "rlim_max");
    }

}
