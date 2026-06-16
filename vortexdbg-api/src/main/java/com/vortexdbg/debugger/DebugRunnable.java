package com.vortexdbg.debugger;

public interface DebugRunnable<T> {

    T runWithArgs(String[] args) throws Exception;

}
