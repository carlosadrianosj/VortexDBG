package com.vortexdbg

import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.debugger.Debugger
import com.vortexdbg.debugger.DebuggerType
import com.vortexdbg.file.FileSystem
import com.vortexdbg.file.NewFileIO
import com.vortexdbg.listener.TraceCodeListener
import com.vortexdbg.listener.TraceReadListener
import com.vortexdbg.listener.TraceSystemMemoryWriteListener
import com.vortexdbg.listener.TraceWriteListener
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.MemoryTracker
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.serialize.Serializable
import com.vortexdbg.spi.ArmDisassembler
import com.vortexdbg.spi.Dlfcn
import com.vortexdbg.spi.LibraryFile
import com.vortexdbg.spi.SyscallHandler
import com.vortexdbg.thread.ThreadDispatcher
import com.vortexdbg.unwind.Unwinder

import java.io.Closeable
import java.io.File
import java.net.URL

/**
 * Top-level CPU emulator: owns the backend, memory, modules and the running
 * process state, and is the main entry point for executing emulated code.
 */
interface Emulator<T : NewFileIO> : Closeable, ArmDisassembler, Serializable {

    fun getPointerSize(): Int

    fun is64Bit(): Boolean
    fun is32Bit(): Boolean

    fun getPageAlign(): Int

    /**
     * trace memory read
     */
    fun traceRead(): TraceHook
    fun traceRead(begin: Long, end: Long): TraceHook
    fun traceRead(begin: Long, end: Long, listener: TraceReadListener?): TraceHook

    /**
     * trace memory write
     */
    fun traceWrite(): TraceHook
    fun traceWrite(begin: Long, end: Long): TraceHook
    fun traceWrite(begin: Long, end: Long, listener: TraceWriteListener?): TraceHook

    fun setTraceSystemMemoryWrite(begin: Long, end: Long, listener: TraceSystemMemoryWriteListener)

    /**
     * trace instruction
     * note: low performance
     */
    fun traceCode(): TraceHook
    fun traceCode(begin: Long, end: Long): TraceHook
    fun traceCode(begin: Long, end: Long, listener: TraceCodeListener?): TraceHook

    fun eFunc(begin: Long, vararg arguments: Number): Number

    fun eEntry(begin: Long, sp: Long): Number

    /**
     * emulate signal handler
     * @param sig signal number
     * @return `true` means called handler function.
     */
    fun emulateSignal(sig: Int): Boolean

    /**
     * @return `true` while the emulator core is actively executing
     */
    fun isRunning(): Boolean

    /**
     * show all registers
     */
    fun showRegs()

    /**
     * show registers
     */
    fun showRegs(vararg regs: Int)

    fun loadLibrary(libraryFile: File): Module
    fun loadLibrary(libraryFile: File, forceCallInit: Boolean): Module

    fun getMemory(): Memory

    fun getBackend(): Backend

    fun getPid(): Int

    fun getProcessName(): String

    fun attach(): Debugger

    fun attach(type: DebuggerType): Debugger

    fun getFileSystem(): FileSystem<T>

    fun getSvcMemory(): SvcMemory

    fun getSyscallHandler(): SyscallHandler<T>

    fun getFamily(): Family
    fun createURLibraryFile(url: URL, libName: String): LibraryFile

    fun getDlfcn(): Dlfcn

    /**
     * @param timeout  Duration to emulate the code (in microseconds). When this value is 0, we will emulate the code in infinite time, until the code is finished.
     */
    fun setTimeout(timeout: Long)

    fun <V : RegisterContext> getContext(): V

    fun getUnwinder(): Unwinder

    /**
     * Start tracking memory allocations (mmap/munmap/brk) for leak detection.
     * Use with try-with-resources: close() restores previous listener and prints the leak report.
     */
    fun traceMemoryLeaks(): MemoryTracker {
        return MemoryTracker(this)
    }

    fun pushContext(off: Int)
    fun popContext(): Int

    fun getThreadDispatcher(): ThreadDispatcher

    fun getReturnAddress(): Long

    fun set(key: String, value: Any?)
    fun <V> get(key: String): V?

}
