package com.github.unidbg.debugger;

import com.github.unidbg.pointer.UnidbgPointer;

public interface Breaker {

    default void debug() {
        debug(null);
    }

    void debug(String reason);

    void brk(UnidbgPointer pc, int svcNumber);

}
