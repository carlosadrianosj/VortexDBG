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
     * 分配内存
     * @param length 大小
     * @param runtime <code>true</code>表示使用mmap按页大小分配，相应的调用MemoryBlock.free方法则使用munmap释放，<code>false</code>表示使用libc.malloc分配，相应的调用MemoryBlock.free方法则使用libc.free释放
     */
    fun malloc(length: Int, runtime: Boolean): MemoryBlock
    fun mmap(length: Int, prot: Int): VortexdbgPointer
    fun munmap(start: Long, length: Int): Int

    /**
     * set errno
     */
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

        const val MMAP_BASE: Long = 0x12000000L //0x1fffe180e , limited by MMIO_TRAP_ADDRESS
    }

}
