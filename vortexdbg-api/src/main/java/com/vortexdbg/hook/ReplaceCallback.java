package com.vortexdbg.hook;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.HookStatus;

public abstract class ReplaceCallback {

    public  HookStatus onCall(Emulator<?> emulator, long originFunction) {
        return HookStatus.RET(emulator, originFunction);
    }

    public  HookStatus onCall(Emulator<?> emulator, HookContext context, long originFunction) {
        return onCall(emulator, originFunction);
    }

    public void postCall(Emulator<?> emulator, HookContext context) {
    }

}
