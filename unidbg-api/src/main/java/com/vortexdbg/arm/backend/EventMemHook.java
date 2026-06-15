package com.vortexdbg.arm.backend;

public interface EventMemHook extends Detachable {

    enum UnmappedType {
        Read,
        Write,
        Fetch
    }

    boolean hook(Backend backend, long address, int size, long value, Object user, UnmappedType unmappedType);

}
