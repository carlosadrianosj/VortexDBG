package com.vortexdbg.ios.struct.kernel;

import com.vortexdbg.pointer.UnidbgStructure;
import com.sun.jna.Pointer;

import java.util.Arrays;
import java.util.List;

public class ThreadBasicInfoReply extends UnidbgStructure {

    public ThreadBasicInfoReply(Pointer p) {
        super(p);
    }

    public NDR_record NDR;
    public int retCode;
    public int outCnt;
    public ThreadBasicInfo info;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("NDR", "retCode", "outCnt", "info");
    }

}
