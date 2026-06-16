package com.vortexdbg.virtualmodule

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.memory.Memory
import com.vortexdbg.pointer.VortexdbgPointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.HashMap

abstract class VirtualModule<T> protected constructor(emulator: Emulator<*>, extra: T?, name: String) {

    private val name: String = name
    private val symbols: MutableMap<String, VortexdbgPointer> = HashMap()

    protected constructor(emulator: Emulator<*>, name: String) : this(emulator, null, name)

    init {
        onInitialize(emulator, extra, symbols)
    }

    protected abstract fun onInitialize(emulator: Emulator<*>, extra: T?, symbols: MutableMap<String, VortexdbgPointer>)

    fun register(memory: Memory): Module {
        if (name.trim().isEmpty()) {
            throw IllegalArgumentException("name is empty")
        }
        if (symbols.isEmpty()) {
            throw IllegalArgumentException("symbols is empty")
        }

        if (log.isDebugEnabled) {
            log.debug("Register virtual module[{}]: ({})", name, symbols)
        }
        return memory.loadVirtualModule(name, symbols)
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(VirtualModule::class.java)
    }

}
