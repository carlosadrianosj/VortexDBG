package com.vortexdbg.arm.backend

interface BlockHook : Detachable {

    fun hookBlock(backend: Backend, address: Long, size: Int, user: Any?)

}
