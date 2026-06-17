package com.vortexdbg.linux

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.spi.InitFunction
import com.sun.jna.Pointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal class AbsoluteInitFunction(load_base: Long, libName: String, private val ptr: VortexdbgPointer) :
    InitFunction(load_base, libName, getFuncAddress(ptr)) {

    override fun getAddress(): Long {
        return address
    }

    override fun call(emulator: Emulator<*>): Long {
        var address = getFuncAddress(ptr)
        if (address == 0L) {
            address = this.address
        }

        if (emulator.is32Bit()) {
            address = address.toInt().toLong()
        }

        if (address == 0L || address == -1L) {
            if (log.isDebugEnabled) {
                log.debug("[{}]CallInitFunction: address=0x{}, ptr={}, func={}", libName, java.lang.Long.toHexString(address), ptr, ptr.getPointer(0L))
            }
            return address
        }

        val pointer: Pointer = VortexdbgPointer.pointer(emulator, address)
        log.debug("[{}]CallInitFunction: {}", libName, pointer)
        val start = System.currentTimeMillis()

        emulator.eFunc(address)
        if (log.isDebugEnabled) {
            System.err.println("[" + libName + "]CallInitFunction: " + pointer + ", offset=" + (System.currentTimeMillis() - start) + "ms")
        }
        return address
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AbsoluteInitFunction::class.java)

        private fun getFuncAddress(ptr: VortexdbgPointer): Long {
            val func = ptr.getPointer(0L)
            return if (func == null) 0 else func.peer
        }
    }

}
