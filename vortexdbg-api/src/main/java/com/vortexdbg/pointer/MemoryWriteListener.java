package com.vortexdbg.pointer;

public interface MemoryWriteListener {

    void onSystemWrite(long addr, byte[] data);

}
