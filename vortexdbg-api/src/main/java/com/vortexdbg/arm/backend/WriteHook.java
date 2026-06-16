package com.vortexdbg.arm.backend;

public interface WriteHook extends Detachable {

    void hook(Backend backend, long address, int size, long value, Object user);

}
