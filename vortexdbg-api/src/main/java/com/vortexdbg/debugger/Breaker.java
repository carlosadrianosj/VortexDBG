package com.vortexdbg.debugger;

import com.vortexdbg.pointer.VortexdbgPointer;

public interface Breaker {

    default void debug() {
        debug(null);
    }

    void debug(String reason);

    void brk(VortexdbgPointer pc, int svcNumber);

}
