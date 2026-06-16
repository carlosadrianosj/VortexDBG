package com.vortexdbg.memory;

import com.vortexdbg.pointer.VortexdbgPointer;
import com.sun.jna.Pointer;
import unicorn.UnicornConst;

public class MemoryBlockImpl implements MemoryBlock {

    public static MemoryBlock alloc(Memory memory, int length) {
        VortexdbgPointer pointer = memory.mmap(length, UnicornConst.UC_PROT_READ | UnicornConst.UC_PROT_WRITE);
        return new MemoryBlockImpl(memory, pointer);
    }

    public static MemoryBlock allocExecutable(Memory memory, int length) {
        VortexdbgPointer pointer = memory.mmap(length, UnicornConst.UC_PROT_READ | UnicornConst.UC_PROT_EXEC);
        return new MemoryBlockImpl(memory, pointer);
    }

    private final Memory memory;
    private final VortexdbgPointer pointer;

    private MemoryBlockImpl(Memory memory, VortexdbgPointer pointer) {
        this.memory = memory;
        this.pointer = pointer;
    }

    @Override
    public VortexdbgPointer getPointer() {
        return pointer;
    }

    @Override
    public boolean isSame(Pointer pointer) {
        return this.pointer.equals(pointer);
    }

    @Override
    public void free() {
        memory.munmap(pointer.peer, (int) pointer.getSize());
    }

}
