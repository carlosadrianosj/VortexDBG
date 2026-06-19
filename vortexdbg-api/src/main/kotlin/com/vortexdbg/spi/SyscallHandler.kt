package com.vortexdbg.spi

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.InterruptHook
import com.vortexdbg.debugger.Breaker
import com.vortexdbg.file.FileIO
import com.vortexdbg.file.IOResolver
import com.vortexdbg.file.NewFileIO
import com.vortexdbg.serialize.Serializable
import com.vortexdbg.thread.MainTask
import com.vortexdbg.unix.FileListener

/** Dispatches guest syscalls/SWIs and brokers file I/O for the emulated process. */
interface SyscallHandler<T : NewFileIO> : InterruptHook, Serializable {

    /** Registers an I/O resolver. Resolvers added later take precedence over earlier ones. */
    fun addIOResolver(resolver: IOResolver<T>)

    fun open(emulator: Emulator<T>, pathname: String, oflags: Int): Int

    fun setVerbose(verbose: Boolean)
    fun isVerbose(): Boolean
    fun setFileListener(fileListener: FileListener)

    fun setBreaker(breaker: Breaker)

    fun setEnableThreadDispatcher(threadDispatcherEnabled: Boolean)

    fun createSignalHandlerTask(emulator: Emulator<*>, sig: Int): MainTask?

    fun getFileIO(fd: Int): FileIO?

    fun closeFileIO(fd: Int)

    fun addFileIO(io: T): Int

    fun destroy()

    companion object {
        const val DARWIN_SWI_SYSCALL = 0x80
    }

}
