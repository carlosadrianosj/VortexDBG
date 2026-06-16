package com.vortexdbg.spi

import com.vortexdbg.arm.context.RegisterContext

import com.vortexdbg.Emulator
import com.vortexdbg.Symbol
import com.vortexdbg.hook.HookListener
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.serialize.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.DataOutput

abstract class Dlfcn protected constructor(svcMemory: SvcMemory) : HookListener, Serializable {

    @JvmField
    protected val error: VortexdbgPointer

    init {
        error = svcMemory.allocate(0x80, "Dlfcn.error")
        assert(error != null)
        error.setMemory(0, 0x80L, 0.toByte())
    }

    protected fun dlsym(emulator: Emulator<*>, handle: Long, symbolName: String): Long {
        val memory: Memory = emulator.getMemory()
        val symbol: Symbol? = memory.dlsym(handle, symbolName)
        if (symbol == null) {
            log.info("Find symbol \"{}\" failed: handle=0x{}, LR={}", symbolName, java.lang.Long.toHexString(handle), emulator.getContext<RegisterContext>().getLRPointer())
            this.error.setString(0, "Find symbol $symbolName failed")
            return 0
        }
        return symbol.getAddress()
    }

    override fun serialize(out: DataOutput) {
        throw UnsupportedOperationException()
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(Dlfcn::class.java)
    }
}
