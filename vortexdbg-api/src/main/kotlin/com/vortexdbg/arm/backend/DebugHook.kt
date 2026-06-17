package com.vortexdbg.arm.backend

interface DebugHook : CodeHook {

    fun onBreak(backend: Backend, address: Long, size: Int, user: Any?)

}
