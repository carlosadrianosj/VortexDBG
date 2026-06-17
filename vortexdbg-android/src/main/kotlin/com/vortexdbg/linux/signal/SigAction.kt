package com.vortexdbg.linux.signal

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

abstract class SigAction(p: Pointer?) : VortexdbgStructure(p) {

    abstract fun getSaHandler(): Long
    abstract fun setSaHandler(sa_handler: Long)

    abstract fun getSaRestorer(): Long
    abstract fun setSaRestorer(sa_restorer: Long)

    open fun needSigInfo(): Boolean {
        return (getFlags() and SA_SIGINFO) != 0
    }

    abstract fun getMask(): Long

    abstract fun setMask(mask: Long)

    abstract fun getFlags(): Int

    abstract fun setFlags(flags: Int)

    companion object {
        private const val SA_SIGINFO = 0x00000004

        @JvmStatic
        fun create(emulator: Emulator<*>, ptr: Pointer?): SigAction? {
            if (ptr == null) {
                return null
            }
            val action: SigAction = if (emulator.is32Bit()) {
                SigAction32(ptr)
            } else {
                SigAction64(ptr)
            }
            action.unpack()
            return action
        }
    }

}
