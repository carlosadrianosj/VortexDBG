package com.vortexdbg.hook.hookzz;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.context.RegisterContext;

public abstract class InstrumentCallback<T extends RegisterContext> {

    public abstract void dbiCall(Emulator<?> emulator, T ctx, HookEntryInfo info);

}
