package com.vortexdbg.arm.backend.kvm

interface KvmCallback {

    fun handleException(esr: Long, far: Long, elr: Long, spsr: Long, pc: Long): Boolean

    companion object {
        const val EC_DATAABORT = 0x24

        const val ARM_EL_ISV_SHIFT = 24
        const val ARM_EL_ISV = (1 shl ARM_EL_ISV_SHIFT)
    }

}
