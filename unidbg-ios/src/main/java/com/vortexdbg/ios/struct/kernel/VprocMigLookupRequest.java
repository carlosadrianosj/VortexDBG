package com.vortexdbg.ios.struct.kernel;

import com.vortexdbg.debugger.ida.Utils;
import com.vortexdbg.pointer.UnidbgStructure;
import com.sun.jna.Pointer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

public class VprocMigLookupRequest extends UnidbgStructure {

    public VprocMigLookupRequest(Pointer p) {
        super(p);
    }

    public NDR_record NDR;
    public byte[] serviceName = new byte[128];

    public String getServiceName() {
        return Utils.readCString(ByteBuffer.wrap(serviceName));
    }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("NDR", "serviceName");
    }

}
