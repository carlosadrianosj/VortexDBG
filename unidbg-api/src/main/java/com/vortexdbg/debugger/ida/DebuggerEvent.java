package com.vortexdbg.debugger.ida;

import com.vortexdbg.Emulator;

public abstract class DebuggerEvent {

    public abstract byte[] pack(Emulator<?> emulator);

}
