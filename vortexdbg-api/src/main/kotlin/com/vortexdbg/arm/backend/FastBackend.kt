package com.vortexdbg.arm.backend

import com.vortexdbg.Emulator
import com.vortexdbg.debugger.BreakPoint
import com.vortexdbg.debugger.BreakPointCallback
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer

abstract class FastBackend(@JvmField protected val emulator: Emulator<*>) : AbstractBackend() {

    private class SoftBreakPoint(
        @JvmField val address: Long,
        @JvmField val backup: ByteArray,
        @JvmField val callback: BreakPointCallback?,
        @JvmField val thumb: Boolean
    ) : BreakPoint {
        override fun setTemporary(temporary: Boolean) {
        }
        override fun isTemporary(): Boolean {
            return true
        }
        override fun getCallback(): BreakPointCallback? {
            return callback
        }
        override fun isThumb(): Boolean {
            return thumb
        }
    }

    private var svcNumber = 1
    private val softBreakpointMap: MutableMap<Int, SoftBreakPoint> = HashMap()

    override fun addBreakPoint(address: Long, callback: BreakPointCallback?, thumb: Boolean): BreakPoint {
        val svcNumber = ++this.svcNumber // begin with 2
        val code = addSoftBreakPoint(address, svcNumber, thumb)

        val pointer: Pointer = VortexdbgPointer.pointer(emulator, address)
        val backup = pointer.getByteArray(0L, code.size)
        pointer.write(0L, code, 0, code.size)
        val breakPoint = SoftBreakPoint(address, backup, callback, thumb)
        softBreakpointMap[svcNumber] = breakPoint
        return breakPoint
    }

    protected abstract fun addSoftBreakPoint(address: Long, svcNumber: Int, thumb: Boolean): ByteArray

    override fun removeBreakPoint(address: Long): Boolean {
        var address = address
        address = address and 1L.inv()

        val iterator = softBreakpointMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val breakPoint = entry.value
            if (address == breakPoint.address) {
                val pointer: Pointer = VortexdbgPointer.pointer(emulator, address)
                pointer.write(0L, breakPoint.backup, 0, breakPoint.backup.size)
                iterator.remove()
                return true
            }
        }
        return false
    }

    override fun setSingleStep(singleStep: Int) {
    }

    final override fun setFastDebug(fastDebug: Boolean) {
    }

}
