package com.vortexdbg.arm.backend

interface InterruptHook : Detachable {

    fun hook(backend: Backend, intno: Int, swi: Int, user: Any?)

}
