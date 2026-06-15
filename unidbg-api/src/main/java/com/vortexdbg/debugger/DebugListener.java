package com.vortexdbg.debugger;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.CodeHistory;

public interface DebugListener {

    boolean canDebug(Emulator<?> emulator, CodeHistory currentCode);

}
