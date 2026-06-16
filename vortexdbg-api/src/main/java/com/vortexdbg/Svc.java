package com.vortexdbg;

import com.vortexdbg.memory.SvcMemory;
import com.vortexdbg.pointer.VortexdbgPointer;

public interface Svc {

    int PRE_CALLBACK_SYSCALL_NUMBER = 0x8866;
    int POST_CALLBACK_SYSCALL_NUMBER = 0x8888;

    VortexdbgPointer onRegister(SvcMemory svcMemory, int svcNumber);

    long handle(Emulator<?> emulator);

    void handlePreCallback(Emulator<?> emulator);
    void handlePostCallback(Emulator<?> emulator);

    String getName();

}
