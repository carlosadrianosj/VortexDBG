package com.vortexdbg.debugger;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.FunctionCall;

public abstract class FunctionCallListener {

    public abstract void onCall(Emulator<?> emulator, long callerAddress, long functionAddress);

    public abstract void postCall(Emulator<?> emulator, long callerAddress, long functionAddress, Number[] args);

    public void onDebugPushFunction(Emulator<?> emulator, FunctionCall call) {
    }
    public void onDebugPopFunction(Emulator<?> emulator, long address, FunctionCall call) {
    }

}
