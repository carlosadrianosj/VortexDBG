package com.vortexdbg.arm.backend.dynarmic

import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.EventMemHook
import unicorn.UnicornConst

class EventMemHookNotifier(
    private val callback: EventMemHook,
    private val type: Int,
    private val user_data: Any?
) {

    fun handleMemoryReadFailed(backend: Backend, vaddr: Long, size: Int) {
        if ((type and UnicornConst.UC_HOOK_MEM_READ_UNMAPPED) != 0) {
            callback.hook(backend, vaddr, size, 0, user_data, EventMemHook.UnmappedType.Read)
        }
    }

    fun handleMemoryWriteFailed(backend: Backend, vaddr: Long, size: Int) {
        if ((type and UnicornConst.UC_HOOK_MEM_WRITE_UNMAPPED) != 0) {
            callback.hook(backend, vaddr, size, 0, user_data, EventMemHook.UnmappedType.Write)
        }
    }
}
