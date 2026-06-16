package com.vortexdbg.arm.backend.hypervisor

internal interface BreakRestorer {

    fun install(hypervisor: Hypervisor)

}
