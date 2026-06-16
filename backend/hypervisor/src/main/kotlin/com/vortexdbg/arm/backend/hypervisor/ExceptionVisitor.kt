package com.vortexdbg.arm.backend.hypervisor

internal abstract class ExceptionVisitor {

    abstract fun onException(hypervisor: Hypervisor, ec: Int, address: Long): Boolean

    companion object {
        fun breakRestorerVisitor(breakRestorer: BreakRestorer): ExceptionVisitor {
            return object : ExceptionVisitor() {
                override fun onException(hypervisor: Hypervisor, ec: Int, address: Long): Boolean {
                    breakRestorer.install(hypervisor)
                    return false
                }
            }
        }
    }

}
