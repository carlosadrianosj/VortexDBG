package com.vortexdbg.ios.struct.kernel;

import com.vortexdbg.pointer.UnidbgStructure;
import com.sun.jna.Pointer;

import java.util.Arrays;
import java.util.List;

public class MachPortSetAttributesReply extends UnidbgStructure {

    public MachPortSetAttributesReply(Pointer p) {
        super(p);
    }

    public NDR_record NDR;
    public int retCode;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("NDR", "retCode");
    }

}
