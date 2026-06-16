package com.vortexdbg.arm;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.VortexdbgPointer;

public class FunctionCall {

    public final long callerAddress;
    public final long functionAddress;
    public final long returnAddress;
    public final Number[] args;

    public FunctionCall(long callerAddress, long functionAddress, long returnAddress, Number[] args) {
        this.callerAddress = callerAddress;
        this.functionAddress = functionAddress;
        this.returnAddress = returnAddress;
        this.args = args;
    }

    public String toReadableString(Emulator<?> emulator) {
        return "FunctionCall{" +
                "callerAddress=" + VortexdbgPointer.pointer(emulator, callerAddress) +
                ", functionAddress=" + VortexdbgPointer.pointer(emulator, functionAddress) +
                ", returnAddress=" + VortexdbgPointer.pointer(emulator, returnAddress) +
                '}';
    }

}
