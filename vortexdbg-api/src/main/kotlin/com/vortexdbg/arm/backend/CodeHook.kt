package com.vortexdbg.arm.backend

interface CodeHook : Detachable {

    fun hook(backend: Backend, address: Long, size: Int, user: Any?)

}
