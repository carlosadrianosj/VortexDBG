package com.vortexdbg.hook;

import com.vortexdbg.Emulator;

public interface HookCallback {

    int onHook(Emulator<?> emulator);

}
