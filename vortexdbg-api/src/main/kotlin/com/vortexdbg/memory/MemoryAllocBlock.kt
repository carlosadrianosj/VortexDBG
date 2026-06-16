package com.vortexdbg.memory

import com.vortexdbg.Emulator
import com.vortexdbg.Symbol
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer

class MemoryAllocBlock private constructor(
    private val pointer: VortexdbgPointer,
    private val emulator: Emulator<*>,
    private val free: Symbol?
) : MemoryBlock {

    override fun getPointer(): VortexdbgPointer {
        return pointer
    }

    override fun isSame(p: Pointer): Boolean {
        return pointer.equals(p)
    }

    override fun free() {
        if (free == null) {
            throw UnsupportedOperationException()
        }

        free.call(emulator, pointer)
    }

    companion object {
        @JvmStatic
        fun malloc(emulator: Emulator<*>, malloc: Symbol, free: Symbol, length: Int): MemoryBlock {
            val number = malloc.call(emulator, length)
            val address = if (emulator.is64Bit()) number.toLong() else number.toInt().toLong() and 0xffffffffL
            val pointer = VortexdbgPointer.pointer(emulator, address)
            return MemoryAllocBlock(pointer, emulator, free)
        }
    }

}
