package com.vortexdbg.arm.backend.hypervisor

import com.vortexdbg.debugger.BreakPoint
import com.vortexdbg.debugger.BreakPointCallback

internal class HypervisorBreakPoint(
    private val slot: Int,
    private val address: Long,
    private val callback: BreakPointCallback?
) : BreakPoint, BreakRestorer {

    fun getAddress(): Long {
        return address
    }

    private var temporary: Boolean = false

    override fun isTemporary(): Boolean {
        return temporary
    }

    override fun setTemporary(temporary: Boolean) {
        this.temporary = temporary
    }

    override fun getCallback(): BreakPointCallback? {
        return callback
    }

    override fun isThumb(): Boolean {
        return false
    }

    override fun install(hypervisor: Hypervisor) {
        hypervisor.install_hw_breakpoint(slot, address)
    }

}
