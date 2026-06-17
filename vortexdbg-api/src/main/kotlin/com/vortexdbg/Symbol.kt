package com.vortexdbg

import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer

abstract class Symbol(private val name: String) {

    abstract fun call(emulator: Emulator<*>, vararg args: Any?): Number

    abstract fun getAddress(): Long

    abstract fun getValue(): Long

    abstract fun isUndef(): Boolean

    fun createNameMemory(svcMemory: SvcMemory): VortexdbgPointer {
        return svcMemory.allocateSymbolName(name)
    }

    open fun createPointer(emulator: Emulator<*>): Pointer {
        return VortexdbgPointer.pointer(emulator, getAddress())
    }

    open fun getName(): String {
        return name
    }

    abstract fun getModuleName(): String

    override fun toString(): String {
        return name
    }

    override fun equals(o: Any?): Boolean {
        if (o !is Symbol) return false
        return getAddress() == o.getAddress()
    }

    override fun hashCode(): Int {
        return java.lang.Long.hashCode(getAddress())
    }
}
