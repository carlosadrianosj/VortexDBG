package com.vortexdbg.arm.backend;

public interface CodeHook extends Detachable {

    void hook(Backend backend, long address, int size, Object user);

}
