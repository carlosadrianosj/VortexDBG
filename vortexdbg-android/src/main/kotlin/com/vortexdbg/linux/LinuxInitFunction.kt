package com.vortexdbg.linux

import com.vortexdbg.Emulator
import com.vortexdbg.spi.InitFunction
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal class LinuxInitFunction(load_base: Long, soName: String, address: Long) :
    InitFunction(load_base, soName, address) {

    override fun getAddress(): Long {
        return load_base + address
    }

    override fun call(emulator: Emulator<*>): Long {
        if (address == 0L || address == -1L) {
            return address
        }

        if (log.isDebugEnabled) {
            log.debug("[{}]CallInitFunction: 0x{}", libName, java.lang.Long.toHexString(address))
        }
        val start = System.currentTimeMillis()
        emulator.eFunc(getAddress())
        if (log.isDebugEnabled) {
            System.err.println("[" + libName + "]CallInitFunction: 0x" + java.lang.Long.toHexString(address) + ", offset=" + (System.currentTimeMillis() - start) + "ms")
        }
        return address
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(LinuxInitFunction::class.java)
    }

}
