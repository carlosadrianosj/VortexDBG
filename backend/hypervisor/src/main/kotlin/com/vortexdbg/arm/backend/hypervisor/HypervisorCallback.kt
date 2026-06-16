package com.vortexdbg.arm.backend.hypervisor

interface HypervisorCallback {

    fun handleException(esr: Long, far: Long, elr: Long, spsr: Long): Boolean
    fun handleUnknownException(ec: Int, esr: Long, far: Long, virtualAddress: Long)

    companion object {
        /** SVC instruction execution in AArch64 state */
        const val EC_AA64_SVC = 0x15
        /** Trapped access to system register (MRS/MSR) */
        const val EC_SYSTEMREGISTERTRAP = 0x18
        /** Instruction Abort from a lower Exception level */
        const val EC_INSNABORT = 0x20
        /** Data Abort from a lower Exception level */
        const val EC_DATAABORT = 0x24
        /** Breakpoint exception from a lower Exception level */
        const val EC_BREAKPOINT = 0x30
        /** Software Step exception from a lower Exception level */
        const val EC_SOFTWARESTEP = 0x32
        /** Watchpoint exception from a lower Exception level */
        const val EC_WATCHPOINT = 0x34
        /** BRK instruction execution in AArch64 state */
        const val EC_AA64_BKPT = 0x3c

        /** ISV (Instruction Syndrome Valid) bit position in ESR_ELx */
        const val ARM_EL_ISV_SHIFT = 24
        const val ARM_EL_ISV = (1 shl ARM_EL_ISV_SHIFT)
    }

}
