package com.vortexdbg.arm.backend

import com.alibaba.fastjson.util.IOUtils
import com.vortexdbg.Emulator
import com.vortexdbg.arm.ARMEmulator
import com.vortexdbg.arm.backend.dynarmic.Dynarmic
import com.vortexdbg.arm.backend.dynarmic.DynarmicCallback
import com.vortexdbg.arm.backend.dynarmic.DynarmicException
import com.vortexdbg.arm.backend.dynarmic.EventMemHookNotifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class DynarmicBackend protected constructor(
    emulator: Emulator<*>,
    protected val dynarmic: Dynarmic
) : FastBackend(emulator), Backend, DynarmicCallback {

    init {
        try {
            this.dynarmic.setDynarmicCallback(this)
        } catch (e: DynarmicException) {
            throw BackendException(e)
        }
    }

    final override fun handleInterpreterFallback(pc: Long, num_instructions: Int): Boolean {
        interruptHookNotifier.notifyCallSVC(this, ARMEmulator.EXCP_UDEF, 0)
        return false
    }

    override fun handleExceptionRaised(pc: Long, exception: Int) {
        if (exception == EXCEPTION_BREAKPOINT) {
            interruptHookNotifier.notifyCallSVC(this, ARMEmulator.EXCP_BKPT, 0)
            return
        }
        try {
            emulator.attach().debug("Dynarmic exception=$exception")
        } catch (e: Exception) {
            e.printStackTrace(System.err)
        }
    }

    override fun handleMemoryReadFailed(vaddr: Long, size: Int) {
        if (eventMemHookNotifier != null) {
            eventMemHookNotifier!!.handleMemoryReadFailed(this, vaddr, size)
        }
    }

    override fun handleMemoryWriteFailed(vaddr: Long, size: Int) {
        if (eventMemHookNotifier != null) {
            eventMemHookNotifier!!.handleMemoryWriteFailed(this, vaddr, size)
        }
    }

    final override fun switchUserMode() {
        // Only user-mode is emulated, there is no emulation of any other privilege levels.
    }

    final override fun enableVFP() {
    }

    protected var until: Long = 0

    @Synchronized
    @Throws(BackendException::class)
    final override fun emu_start(begin: Long, until: Long, timeout: Long, count: Long) {
        if (log.isDebugEnabled) {
            log.debug("emu_start begin=0x{}, until=0x{}, timeout={}, count={}", java.lang.Long.toHexString(begin), java.lang.Long.toHexString(until), timeout, count)
        }
        this.until = until + 4
        try {
            dynarmic.emu_start(begin)
        } catch (e: DynarmicException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun emu_stop() {
        try {
            dynarmic.emu_stop()
        } catch (e: DynarmicException) {
            throw BackendException(e)
        }
    }

    override fun destroy() {
        IOUtils.close(dynarmic)
    }

    @Throws(BackendException::class)
    override fun mem_read(address: Long, size: Long): ByteArray {
        try {
            return dynarmic.mem_read(address, size.toInt())
        } catch (e: DynarmicException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun mem_write(address: Long, bytes: ByteArray) {
        try {
            dynarmic.mem_write(address, bytes)
        } catch (e: DynarmicException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun mem_map(address: Long, size: Long, perms: Int) {
        try {
            dynarmic.mem_map(address, size, perms)
        } catch (e: DynarmicException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun mem_protect(address: Long, size: Long, perms: Int) {
        try {
            dynarmic.mem_protect(address, size, perms)
        } catch (e: DynarmicException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun mem_unmap(address: Long, size: Long) {
        try {
            dynarmic.mem_unmap(address, size)
        } catch (e: DynarmicException) {
            throw BackendException(e)
        }
    }

    private var eventMemHookNotifier: EventMemHookNotifier? = null

    override fun hook_add_new(callback: EventMemHook, type: Int, user_data: Any?) {
        if (eventMemHookNotifier != null) {
            throw IllegalStateException()
        } else {
            eventMemHookNotifier = EventMemHookNotifier(callback, type, user_data)
        }
    }

    protected lateinit var interruptHookNotifier: InterruptHookNotifier

    override fun hook_add_new(callback: InterruptHook, user_data: Any?) {
        if (this::interruptHookNotifier.isInitialized) {
            throw IllegalStateException()
        } else {
            interruptHookNotifier = InterruptHookNotifier(callback, user_data)
        }
    }

    override fun hook_add_new(callback: CodeHook, begin: Long, end: Long, user_data: Any?) {
        throw UnsupportedOperationException()
    }

    override fun debugger_add(callback: DebugHook, begin: Long, end: Long, user_data: Any?) {
    }

    override fun hook_add_new(callback: ReadHook, begin: Long, end: Long, user_data: Any?) {
        throw UnsupportedOperationException()
    }

    override fun hook_add_new(callback: WriteHook, begin: Long, end: Long, user_data: Any?) {
        throw UnsupportedOperationException()
    }

    override fun hook_add_new(callback: BlockHook, begin: Long, end: Long, user_data: Any?) {
        throw UnsupportedOperationException()
    }

    override fun context_alloc(): Long {
        return dynarmic.context_alloc()
    }

    override fun context_free(context: Long) {
        Dynarmic.free(context)
    }

    override fun context_save(context: Long) {
        dynarmic.context_save(context)
    }

    override fun context_restore(context: Long) {
        dynarmic.context_restore(context)
    }

    override fun getMemAllocatedSize(): Long {
        return dynarmic.getMemAllocatedSize()
    }

    override fun getMemResidentSize(): Long {
        return dynarmic.getMemResidentSize()
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(DynarmicBackend::class.java)

        private const val EXCEPTION_BREAKPOINT = 8
    }
}
