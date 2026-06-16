package com.vortexdbg.arm.backend

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.unicorn.Unicorn
import com.vortexdbg.debugger.BreakPoint
import com.vortexdbg.debugger.BreakPointCallback
import unicorn.UnicornConst
import unicorn.UnicornException

internal class Unicorn2Backend @Throws(BackendException::class) constructor(
    private val emulator: Emulator<*>,
    private val is64Bit: Boolean
) : AbstractBackend(), Backend {

    private val unicorn: Unicorn

    init {
        try {
            unicorn = if (is64Bit) {
                Unicorn(UnicornConst.UC_ARCH_ARM64, UnicornConst.UC_MODE_ARM)
            } else {
                Unicorn(UnicornConst.UC_ARCH_ARM, UnicornConst.UC_MODE_ARM)
            }
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    override fun switchUserMode() {
        switchUserMode(is64Bit)
    }

    override fun enableVFP() {
        enableVFP(is64Bit)
    }

    @Throws(BackendException::class)
    override fun reg_read_vector(regId: Int): ByteArray {
        checkVectorRegId(regId, is64Bit)
        try {
            return unicorn.reg_read(regId, 16)
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun reg_write_vector(regId: Int, vector: ByteArray) {
        if (vector.size != 16) {
            throw IllegalStateException("Invalid vector size")
        }
        checkVectorRegId(regId, is64Bit)
        try {
            unicorn.reg_write(regId, vector)
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun reg_read(regId: Int): Number {
        try {
            return unicorn.reg_read(regId)
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun reg_write(regId: Int, value: Number) {
        try {
            unicorn.reg_write(regId, if (is64Bit) value.toLong() else value.toInt().toLong())
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun mem_read(address: Long, size: Long): ByteArray {
        try {
            return unicorn.mem_read(address, size)
        } catch (e: UnicornException) {
            throw BackendException("mem_read address=0x" + java.lang.Long.toHexString(address) + ", size=" + size, e)
        }
    }

    @Throws(BackendException::class)
    override fun mem_write(address: Long, bytes: ByteArray) {
        try {
            unicorn.mem_write(address, bytes)
        } catch (e: UnicornException) {
            throw BackendException("mem_write address=0x" + java.lang.Long.toHexString(address), e)
        }
    }

    @Throws(BackendException::class)
    override fun mem_map(address: Long, size: Long, perms: Int) {
        try {
            unicorn.mem_map(address, size, perms)
        } catch (e: UnicornException) {
            throw BackendException("mem_map address=0x" + java.lang.Long.toHexString(address) + ", size=" + size + ", perms=0x" + Integer.toHexString(perms), e)
        }
    }

    @Throws(BackendException::class)
    override fun mem_protect(address: Long, size: Long, perms: Int) {
        try {
            unicorn.mem_protect(address, size, perms)
        } catch (e: UnicornException) {
            throw BackendException("mem_protect address=0x" + java.lang.Long.toHexString(address) + ", size=" + size + ", perms=0x" + Integer.toHexString(perms), e)
        }
    }

    @Throws(BackendException::class)
    override fun mem_unmap(address: Long, size: Long) {
        try {
            unicorn.mem_unmap(address, size)
        } catch (e: UnicornException) {
            throw BackendException("mem_unmap address=0x" + java.lang.Long.toHexString(address) + ", size=" + size, e)
        }
    }

    override fun addBreakPoint(address: Long, callback: BreakPointCallback?, thumb: Boolean): BreakPoint {
        val breakPoint = BreakPointImpl(callback, thumb)
        unicorn.addBreakPoint(address)
        return breakPoint
    }

    override fun removeBreakPoint(address: Long): Boolean {
        unicorn.removeBreakPoint(address)
        return true
    }

    override fun setSingleStep(singleStep: Int) {
        unicorn.setSingleStep(singleStep)
    }

    override fun setFastDebug(fastDebug: Boolean) {
        unicorn.setFastDebug(fastDebug)
    }

    @Throws(BackendException::class)
    override fun removeJitCodeCache(begin: Long, end: Long) {
        unicorn.removeJitCodeCache(begin, end)
    }

    @Throws(BackendException::class)
    override fun hook_add_new(callback: CodeHook, begin: Long, end: Long, user_data: Any?) {
        try {
            val unHook = unicorn.hook_add_new(
                object : com.vortexdbg.arm.backend.unicorn.CodeHook {
                    override fun hook(u: Unicorn, address: Long, size: Int, user: Any?) {
                        callback.hook(this@Unicorn2Backend, address, size, user)
                    }
                }, begin, end, user_data
            )
            callback.onAttach(unHook::unhook)
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun debugger_add(callback: DebugHook, begin: Long, end: Long, user_data: Any?) {
        try {
            val unHook = unicorn.debugger_add(object : com.vortexdbg.arm.backend.unicorn.DebugHook {
                override fun onBreak(u: Unicorn, address: Long, size: Int, user: Any?) {
                    callback.onBreak(this@Unicorn2Backend, address, size, user)
                }

                override fun hook(u: Unicorn, address: Long, size: Int, user: Any?) {
                    callback.hook(this@Unicorn2Backend, address, size, user)
                }
            }, begin, end, user_data)
            callback.onAttach(unHook::unhook)
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun hook_add_new(callback: ReadHook, begin: Long, end: Long, user_data: Any?) {
        try {
            val unHook = unicorn.hook_add_new(
                object : com.vortexdbg.arm.backend.unicorn.ReadHook {
                    override fun hook(u: Unicorn, address: Long, size: Int, user: Any?) {
                        callback.hook(this@Unicorn2Backend, address, size, user)
                    }
                }, begin, end, user_data
            )
            callback.onAttach(unHook::unhook)
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun hook_add_new(callback: WriteHook, begin: Long, end: Long, user_data: Any?) {
        try {
            val unHook = unicorn.hook_add_new(
                object : com.vortexdbg.arm.backend.unicorn.WriteHook {
                    override fun hook(u: Unicorn, address: Long, size: Int, value: Long, user: Any?) {
                        callback.hook(this@Unicorn2Backend, address, size, value, user)
                    }
                }, begin, end, user_data
            )
            callback.onAttach(unHook::unhook)
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun hook_add_new(callback: EventMemHook, type: Int, user_data: Any?) {
        if ((type and UnicornConst.UC_HOOK_MEM_READ_UNMAPPED) != 0) {
            hookEventMem(callback, UnicornConst.UC_HOOK_MEM_READ_UNMAPPED, user_data, EventMemHook.UnmappedType.Read)
        }
        if ((type and UnicornConst.UC_HOOK_MEM_WRITE_UNMAPPED) != 0) {
            hookEventMem(callback, UnicornConst.UC_HOOK_MEM_WRITE_UNMAPPED, user_data, EventMemHook.UnmappedType.Write)
        }
        if ((type and UnicornConst.UC_HOOK_MEM_FETCH_UNMAPPED) != 0) {
            hookEventMem(callback, UnicornConst.UC_HOOK_MEM_FETCH_UNMAPPED, user_data, EventMemHook.UnmappedType.Fetch)
        }
    }

    private fun hookEventMem(callback: EventMemHook, type: Int, user_data: Any?, unmappedType: EventMemHook.UnmappedType) {
        try {
            val map = unicorn.hook_add_new(
                object : com.vortexdbg.arm.backend.unicorn.EventMemHook {
                    override fun hook(u: Unicorn, address: Long, size: Int, value: Long, user: Any?): Boolean {
                        return callback.hook(this@Unicorn2Backend, address, size, value, user, unmappedType)
                    }
                }, type, user_data
            )
            for (unHook in map.values) {
                callback.onAttach(unHook::unhook)
            }
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun hook_add_new(callback: InterruptHook, user_data: Any?) {
        try {
            val unHook = unicorn.hook_add_new(
                object : com.vortexdbg.arm.backend.unicorn.InterruptHook {
                    override fun hook(u: Unicorn, intno: Int, user: Any?) {
                        val swi = decodeSWI(emulator, this@Unicorn2Backend, is64Bit)
                        callback.hook(this@Unicorn2Backend, intno, swi, user)
                    }
                }, user_data
            )
            callback.onAttach(unHook::unhook)
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun hook_add_new(callback: BlockHook, begin: Long, end: Long, user_data: Any?) {
        try {
            val unHook = unicorn.hook_add_new(
                object : com.vortexdbg.arm.backend.unicorn.BlockHook {
                    override fun hook(u: Unicorn, address: Long, size: Int, user: Any?) {
                        callback.hookBlock(this@Unicorn2Backend, address, size, user)
                    }
                }, begin, end, user_data
            )
            callback.onAttach(unHook::unhook)
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    @Synchronized
    override fun emu_start(begin: Long, until: Long, timeout: Long, count: Long) {
        try {
            unicorn.emu_start(begin, until, timeout, count)
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun emu_stop() {
        try {
            unicorn.emu_stop()
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun destroy() {
        try {
            unicorn.closeAll()
        } catch (e: UnicornException) {
            throw BackendException(e)
        }
    }

    override fun context_restore(context: Long) {
        unicorn.context_restore(context)
    }

    override fun context_save(context: Long) {
        unicorn.context_save(context)
    }

    override fun context_alloc(): Long {
        return unicorn.context_alloc()
    }

    override fun context_free(context: Long) {
        Unicorn.free(context)
    }

    override fun getMemAllocatedSize(): Long {
        return unicorn.memAllocatedSize
    }

    override fun getMemResidentSize(): Long {
        return unicorn.memResidentSize
    }

    private var unHook: Unicorn.UnHook? = null

    override fun registerEmuCountHook(emu_count: Long) {
        if (unHook != null) {
            throw IllegalStateException()
        }
        if (emu_count <= 0) {
            throw IllegalArgumentException()
        } else {
            unHook = unicorn.registerEmuCountHook(emu_count)
        }
    }
}
