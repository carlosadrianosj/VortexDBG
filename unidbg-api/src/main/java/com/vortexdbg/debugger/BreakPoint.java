package com.vortexdbg.debugger;

public interface BreakPoint {

    boolean isTemporary();
    void setTemporary(boolean temporary);
    BreakPointCallback getCallback();
    boolean isThumb();

}
