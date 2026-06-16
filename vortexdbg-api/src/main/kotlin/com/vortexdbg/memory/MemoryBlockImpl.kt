package com.vortexdbg.memory

import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import unicorn.UnicornConst

class MemoryBlockImpl private constructor(
    private val memory: Memory,
    private val pointer: VortexdbgPointer
) : MemoryBlock {

    override fun getPointer(): VortexdbgPointer {
        return pointer
    }

    override fun isSame(pointer: Pointer): Boolean {
        return this.pointer.equals(pointer)
    }

    override fun free() {
        memory.munmap(pointer.peer, pointer.getSize().toInt())
    }

    companion object {
        @JvmStatic
        fun alloc(memory: Memory, length: Int): MemoryBlock {
            val pointer = memory.mmap(length, UnicornConst.UC_PROT_READ or UnicornConst.UC_PROT_WRITE)
            return MemoryBlockImpl(memory, pointer)
        }

        @JvmStatic
        fun allocExecutable(memory: Memory, length: Int): MemoryBlock {
            val pointer = memory.mmap(length, UnicornConst.UC_PROT_READ or UnicornConst.UC_PROT_EXEC)
            return MemoryBlockImpl(memory, pointer)
        }
    }

}
