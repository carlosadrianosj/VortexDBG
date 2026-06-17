package com.vortexdbg.arm.backend

interface ReadHook : Detachable {

    fun hook(backend: Backend, address: Long, size: Int, user: Any?)

}
