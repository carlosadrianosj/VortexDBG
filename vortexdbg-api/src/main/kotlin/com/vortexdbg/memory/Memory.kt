package com.vortexdbg.memory

import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.spi.Loader
import com.vortexdbg.thread.BaseTask
import com.vortexdbg.unix.IO

@Suppress("unused")
interface Memory : IO, Loader, StackMemory {

    fun allocateThreadIndex(): Int
    fun freeThreadIndex(index: Int)
    fun allocateThreadStack(index: Int): VortexdbgPointer
    fun allocateStack(size: Int): VortexdbgPointer
    fun pointer(address: Long): VortexdbgPointer
    fun setStackPoint(sp: Long)
    fun getStackPoint(): Long
    fun getStackBase(): Long
    fun getStackSize(): Int

    fun mmap2(start: Long, length: Int, prot: Int, flags: Int, fd: Int, offset: Int): Long
    fun mprotect(address: Long, length: Int, prot: Int): Int
    fun brk(address: Long): Int

    /**
     * Allocates a memory block of [length] bytes.
     *
     * @param runtime `true` allocates page-aligned via mmap (the returned block frees with munmap);
     *   `false` allocates via the guest's libc malloc (the block frees with libc free)
     */
    fun malloc(length: Int, runtime: Boolean): MemoryBlock
    fun mmap(length: Int, prot: Int): VortexdbgPointer
    fun munmap(start: Long, length: Int): Int

    fun setErrno(errno: Int)

    fun getLastErrno(): Int

    fun getMemoryMap(): Collection<MemoryMap>

    fun setMMapListener(listener: MMapListener?)

    fun getMMapListener(): MMapListener?

    companion object {
        const val STACK_BASE: Long = 0xe5000000L

        const val MAX_THREADS: Int = 16
        const val STACK_SIZE_OF_THREAD_PAGE: Int = MAX_THREADS * BaseTask.THREAD_STACK_PAGE // for thread stack
        const val STACK_SIZE_OF_MAIN_PAGE: Int = 256 // for main stack
        const val STACK_SIZE_OF_PAGE: Int = STACK_SIZE_OF_THREAD_PAGE + STACK_SIZE_OF_MAIN_PAGE

        // Kept well below MMIO_TRAP_ADDRESS (~0x1fffe180e) so mmap growth never collides with the
        // MMIO trap region.
        const val MMAP_BASE: Long = 0x12000000L
    }

}
