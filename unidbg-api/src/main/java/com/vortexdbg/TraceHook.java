package com.vortexdbg;

import java.io.PrintStream;

public interface TraceHook {

    void setRedirect(PrintStream redirect);

    void stopTrace();

}
