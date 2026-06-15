package com.vortexdbg.listener;

import com.vortexdbg.Emulator;

public interface TraceSystemMemoryWriteListener {

    void onWrite(Emulator<?> emulator, long address, byte[] buf);

}
