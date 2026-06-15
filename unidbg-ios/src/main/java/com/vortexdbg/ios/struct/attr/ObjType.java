package com.vortexdbg.ios.struct.attr;

import com.vortexdbg.pointer.UnidbgStructure;
import com.sun.jna.Pointer;

import java.util.Collections;
import java.util.List;

public class ObjType extends UnidbgStructure {

    public ObjType(Pointer p) {
        super(p);
    }

    public int type;

    @Override
    protected List<String> getFieldOrder() {
        return Collections.singletonList("type");
    }

}
