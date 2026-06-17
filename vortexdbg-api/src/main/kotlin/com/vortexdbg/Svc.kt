package com.vortexdbg

import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer

interface Svc {

    fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer

    fun handle(emulator: Emulator<*>): Long

    fun handlePreCallback(emulator: Emulator<*>)
    fun handlePostCallback(emulator: Emulator<*>)

    fun getName(): String?

    companion object {
        const val PRE_CALLBACK_SYSCALL_NUMBER = 0x8866
        const val POST_CALLBACK_SYSCALL_NUMBER = 0x8888
    }

}
