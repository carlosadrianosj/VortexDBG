package com.vortexdbg.arm.backend

interface WriteHook : Detachable {

    fun hook(backend: Backend, address: Long, size: Int, value: Long, user: Any?)

}
