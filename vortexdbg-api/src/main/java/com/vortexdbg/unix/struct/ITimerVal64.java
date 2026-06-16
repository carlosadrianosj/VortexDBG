package com.vortexdbg.unix.struct;

import com.vortexdbg.pointer.VortexdbgStructure;
import com.sun.jna.Pointer;

import java.util.Arrays;
import java.util.List;

public class ITimerVal64 extends VortexdbgStructure {

    public ITimerVal64(Pointer p) {
        super(p);
    }

    public TimeVal64 it_interval; /* timer interval */
    public TimeVal64 it_value; /* current value */

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("it_interval", "it_value");
    }
}
