package com.vortexdbg.arm.backend

interface EventMemHook : Detachable {

    enum class UnmappedType {
        Read,
        Write,
        Fetch
    }

    fun hook(backend: Backend, address: Long, size: Int, value: Long, user: Any?, unmappedType: UnmappedType): Boolean

}
