package com.vortexdbg.debugger;

import com.vortexdbg.pointer.UnidbgPointer;

public interface Breaker {

    default void debug() {
        debug(null);
    }

    void debug(String reason);

    void brk(UnidbgPointer pc, int svcNumber);

}
