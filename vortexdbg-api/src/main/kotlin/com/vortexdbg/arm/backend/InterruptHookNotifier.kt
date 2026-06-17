package com.vortexdbg.arm.backend

class InterruptHookNotifier(private val callback: InterruptHook, private val user_data: Any?) {

    fun notifyCallSVC(backend: Backend, intno: Int, swi: Int) {
        callback.hook(backend, intno, swi, user_data)
    }

}
