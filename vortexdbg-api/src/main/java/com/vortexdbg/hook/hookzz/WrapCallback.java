package com.vortexdbg.hook.hookzz;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.context.RegisterContext;

public abstract class WrapCallback<T extends RegisterContext> {

    public abstract void preCall(Emulator<?> emulator, T ctx, HookEntryInfo info);

    public void postCall(Emulator<?> emulator, T ctx, HookEntryInfo info) {}

}
