package com.vortexdbg.arm.backend;

public interface ReadHook extends Detachable {

    void hook(Backend backend, long address, int size, Object user);

}
