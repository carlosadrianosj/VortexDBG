package com.vortexdbg.arm.backend.unicorn

import com.vortexdbg.thread.ThreadContextSwitchException
import unicorn.UnicornConst
import unicorn.UnicornException
import java.util.Hashtable

class Unicorn @Throws(UnicornException::class) constructor(arch: Int, mode: Int) {

    private open class Tuple(@JvmField var function: Hook?, @JvmField var data: Any?)

    inner class UnHook(private val handle: Long) {
        init {
            newHookList.add(this)
        }

        fun unhook() {
            unhookInternal()
            newHookList.remove(this)
        }

        private var unhooked = false

        internal fun unhookInternal() {
            if (!unhooked && handle != 0L) {
                hook_del(handle)
            }
            unhooked = true
        }
    }

    private inner class NewHook(f: Hook?, d: Any?) : Tuple(f, d) {

        /**
         * for UC_HOOK_BLOCK
         */
        fun onBlock(address: Long, size: Int) {
            val hook = function as BlockHook
            hook.hook(this@Unicorn, address, size, data)
        }

        /**
         * for UC_HOOK_CODE
         */
        fun onCode(address: Long, size: Int) {
            val hook = function as CodeHook
            hook.hook(this@Unicorn, address, size, data)
        }

        /**
         * on breakpoint hit
         */
        fun onBreak(address: Long, size: Int) {
            val hook = function as DebugHook
            hook.onBreak(this@Unicorn, address, size, data)
        }

        /**
         * for UC_HOOK_MEM_READ
         */
        fun onRead(address: Long, size: Int) {
            val hook = function as ReadHook
            hook.hook(this@Unicorn, address, size, data)
        }

        /**
         * for UC_HOOK_MEM_WRITE
         */
        fun onWrite(address: Long, size: Int, value: Long) {
            val hook = function as WriteHook
            hook.hook(this@Unicorn, address, size, value, data)
        }

        /**
         * for UC_HOOK_INTR
         */
        fun onInterrupt(intno: Int) {
            val hook = function as InterruptHook
            hook.hook(this@Unicorn, intno, data)
        }

        /**
         * for UC_HOOK_MEM_*
         */
        fun onMemEvent(type: Int, address: Long, size: Int, value: Long): Boolean {
            val hook = function as EventMemHook
            return hook.hook(this@Unicorn, address, size, value, data)
        }
    }

    /**
     * Read register value.
     *
     * @param regid  Register ID that is to be retrieved.
     * @param regsz  Size of the register being retrieved.
     * @return Byte array containing the requested register value.
     */
    @Throws(UnicornException::class)
    fun reg_read(regid: Int, regsz: Int): ByteArray {
        return reg_read(nativeHandle, regid, regsz)
    }

    /**
     * Write to register.
     *
     * @param  regid  Register ID that is to be modified.
     * @param  value  Array containing value that will be written into register @regid
     */
    @Throws(UnicornException::class)
    fun reg_write(regid: Int, value: ByteArray) {
        reg_write(nativeHandle, regid, value)
    }

    /**
     * Read register value.
     *
     * @param regid  Register ID that is to be retrieved.
     * @return Number containing the requested register value.
     */
    @Throws(UnicornException::class)
    fun reg_read(regid: Int): Long {
        return reg_read(nativeHandle, regid)
    }

    /**
     * Write to register.
     *
     * @param  regid  Register ID that is to be modified.
     * @param  value  Number containing the new register value
     */
    @Throws(UnicornException::class)
    fun reg_write(regid: Int, value: Long) {
        reg_write(nativeHandle, regid, value)
    }

    fun registerEmuCountHook(emu_count: Long): UnHook {
        val hook = NewHook(object : CodeHook {
            override fun hook(u: Unicorn, address: Long, size: Int, user: Any?) {
                throw ThreadContextSwitchException()
            }
        }, null)
        return UnHook(register_emu_count_hook(nativeHandle, emu_count, hook))
    }

    /**
     * Read memory contents.
     *
     * @param address  Start addres of the memory region to be read.
     * @param size     Number of bytes to be retrieved.
     * @return Byte array containing the contents of the requested memory range.
     */
    @Throws(UnicornException::class)
    fun mem_read(address: Long, size: Long): ByteArray {
        return mem_read(nativeHandle, address, size)
    }

    /**
     * Write to memory.
     *
     * @param  address  Start addres of the memory region to be written.
     * @param  bytes    The values to be written into memory. bytes.length bytes will be written.
     */
    @Throws(UnicornException::class)
    fun mem_write(address: Long, bytes: ByteArray) {
        mem_write(nativeHandle, address, bytes)
    }

    /**
     * Map a range of memory.
     *
     * @param address Base address of the memory range
     * @param size    Size of the memory block.
     * @param perms   Permissions on the memory block. A combination of UC_PROT_READ, UC_PROT_WRITE, UC_PROT_EXEC
     */
    @Throws(UnicornException::class)
    fun mem_map(address: Long, size: Long, perms: Int) {
        mem_map(nativeHandle, address, size, perms)
    }

    /**
     * Change permissions on a range of memory.
     *
     * @param address Base address of the memory range
     * @param size    Size of the memory block.
     * @param perms   New permissions on the memory block. A combination of UC_PROT_READ, UC_PROT_WRITE, UC_PROT_EXEC
     */
    @Throws(UnicornException::class)
    fun mem_protect(address: Long, size: Long, perms: Int) {
        mem_protect(nativeHandle, address, size, perms)
    }

    /**
     * Unmap a range of memory.
     *
     * @param address Base address of the memory range
     * @param size    Size of the memory block.
     */
    @Throws(UnicornException::class)
    fun mem_unmap(address: Long, size: Long) {
        mem_unmap(nativeHandle, address, size)
    }

    fun setFastDebug(fastDebug: Boolean) {
        setFastDebug(nativeHandle, fastDebug)
    }

    fun setSingleStep(singleStep: Int) {
        setSingleStep(nativeHandle, singleStep)
    }

    fun addBreakPoint(address: Long) {
        addBreakPoint(nativeHandle, address)
    }

    fun removeBreakPoint(address: Long) {
        removeBreakPoint(nativeHandle, address)
    }

    /**
     * Emulate machine code in a specific duration of time.
     *
     * @param begin    Address where emulation starts
     * @param until    Address where emulation stops (i.e when this address is hit)
     * @param timeout  Duration to emulate the code (in microseconds). When this value is 0, we will emulate the code in infinite time, until the code is finished.
     * @param count    The number of instructions to be emulated. When this value is 0, we will emulate all the code available, until the code is finished.
     */
    @Throws(UnicornException::class)
    fun emu_start(begin: Long, until: Long, timeout: Long, count: Long) {
        emu_start(nativeHandle, begin, until, timeout, count)
    }

    /**
     * Stop emulation (which was started by emu_start() ).
     * This is typically called from callback functions registered via tracing APIs.
     * NOTE: for now, this will stop the execution only after the current block.
     */
    @Throws(UnicornException::class)
    fun emu_stop() {
        emu_stop(nativeHandle)
    }

    /**
     * Allocate a region that can be used with uc_context_{save,restore} to perform
     * quick save/rollback of the CPU context, which includes registers and some
     * internal metadata. Contexts may not be shared across engine instances with
     * differing arches or modes.
     *
     * @return context handle for use with save/restore.
     */
    fun context_alloc(): Long {
        return context_alloc(nativeHandle)
    }

    /**
     * Save a copy of the internal CPU context.
     * This API should be used to efficiently make or update a saved copy of the
     * internal CPU state.
     *
     * @param context handle previously returned by context_alloc.
     */
    fun context_save(context: Long) {
        context_save(nativeHandle, context)
    }

    /**
     * Restore the current CPU context from a saved copy.
     * This API should be used to roll the CPU context back to a previous
     * state saved by uc_context_save().
     *
     * @param context handle previously returned by context_alloc.
     */
    fun context_restore(context: Long) {
        context_restore(nativeHandle, context)
    }

    val memAllocatedSize: Long
        get() = mem_allocated_size(nativeHandle)

    val memResidentSize: Long
        get() = mem_resident_size(nativeHandle)

    fun removeJitCodeCache(begin: Long, end: Long) {
        removeCache(nativeHandle, begin, end)
    }

    @Throws(UnicornException::class)
    fun hook_add_new(callback: BlockHook, begin: Long, end: Long, user_data: Any?): UnHook {
        val hook = NewHook(callback, user_data)
        val handle = registerHook(nativeHandle, UnicornConst.UC_HOOK_BLOCK, begin, end, hook)
        return UnHook(handle)
    }

    @Throws(UnicornException::class)
    fun hook_add_new(callback: InterruptHook, user_data: Any?): UnHook {
        val hook = NewHook(callback, user_data)
        val handle = registerHook(nativeHandle, UnicornConst.UC_HOOK_INTR, hook)
        return UnHook(handle)
    }

    @Throws(UnicornException::class)
    fun hook_add_new(callback: EventMemHook, type: Int, user_data: Any?): Map<Int, UnHook> {
        //test all of the EventMem related bits in type
        val map = HashMap<Int, UnHook>(eventMemMap.size)
        for (htype in eventMemMap.keys) {
            if ((type and htype) != 0) { //the 'htype' bit is set in type
                val hook = NewHook(callback, user_data)
                val handle = registerHook(nativeHandle, htype, hook)
                map[htype] = UnHook(handle)
            }
        }
        return map
    }

    @Throws(UnicornException::class)
    fun hook_add_new(callback: ReadHook, begin: Long, end: Long, user_data: Any?): UnHook {
        val hook = NewHook(callback, user_data)
        val handle = registerHook(nativeHandle, UnicornConst.UC_HOOK_MEM_READ, begin, end, hook)
        return UnHook(handle)
    }

    @Throws(UnicornException::class)
    fun hook_add_new(callback: WriteHook, begin: Long, end: Long, user_data: Any?): UnHook {
        val hook = NewHook(callback, user_data)
        val handle = registerHook(nativeHandle, UnicornConst.UC_HOOK_MEM_WRITE, begin, end, hook)
        return UnHook(handle)
    }

    @Throws(UnicornException::class)
    fun hook_add_new(callback: CodeHook, begin: Long, end: Long, user_data: Any?): UnHook {
        val hook = NewHook(callback, user_data)
        val handle = registerHook(nativeHandle, UnicornConst.UC_HOOK_CODE, begin, end, hook)
        return UnHook(handle)
    }

    @Throws(UnicornException::class)
    fun debugger_add(callback: DebugHook, begin: Long, end: Long, user_data: Any?): UnHook {
        val hook = NewHook(callback, user_data)
        val handle = registerDebugger(nativeHandle, begin, end, hook)
        return UnHook(handle)
    }

    private val newHookList: MutableList<UnHook> = ArrayList()
    private val nativeHandle: Long = nativeInitialize(arch, mode)

    @Throws(UnicornException::class)
    fun closeAll() {
        for (unHook in newHookList) {
            unHook.unhookInternal()
        }
        nativeDestroy(nativeHandle)
    }

    companion object {

        private val eventMemMap = Hashtable<Int, Int>()

        init {
            eventMemMap.put(UnicornConst.UC_HOOK_MEM_READ_UNMAPPED, UnicornConst.UC_MEM_READ_UNMAPPED)
            eventMemMap.put(UnicornConst.UC_HOOK_MEM_WRITE_UNMAPPED, UnicornConst.UC_MEM_WRITE_UNMAPPED)
            eventMemMap.put(UnicornConst.UC_HOOK_MEM_FETCH_UNMAPPED, UnicornConst.UC_MEM_FETCH_UNMAPPED)
            eventMemMap.put(UnicornConst.UC_HOOK_MEM_READ_PROT, UnicornConst.UC_MEM_READ_PROT)
            eventMemMap.put(UnicornConst.UC_HOOK_MEM_WRITE_PROT, UnicornConst.UC_MEM_WRITE_PROT)
            eventMemMap.put(UnicornConst.UC_HOOK_MEM_FETCH_PROT, UnicornConst.UC_MEM_FETCH_PROT)
            eventMemMap.put(UnicornConst.UC_HOOK_MEM_READ, UnicornConst.UC_MEM_READ)
            eventMemMap.put(UnicornConst.UC_HOOK_MEM_WRITE, UnicornConst.UC_MEM_WRITE)
            eventMemMap.put(UnicornConst.UC_HOOK_MEM_FETCH, UnicornConst.UC_MEM_FETCH)
            eventMemMap.put(UnicornConst.UC_HOOK_MEM_READ_AFTER, UnicornConst.UC_MEM_READ_AFTER)
        }

        /**
         * Native access to uc_open
         *
         * @param  arch  Architecture type (UC_ARCH_*)
         * @param  mode  Hardware mode. This is combined of UC_MODE_*
         */
        @JvmStatic
        @Throws(UnicornException::class)
        private external fun nativeInitialize(arch: Int, mode: Int): Long

        /**
         * Close the underlying uc_engine* eng associated with this Unicorn object
         *
         */
        @JvmStatic
        @Throws(UnicornException::class)
        private external fun nativeDestroy(handle: Long)

        /**
         * Hook registration helper for unhook.
         *
         * @param handle   Unicorn uch returned for registered hook function
         */
        @JvmStatic
        @Throws(UnicornException::class)
        private external fun hook_del(handle: Long)

        @JvmStatic
        @Throws(UnicornException::class)
        private external fun reg_read(handle: Long, regid: Int, regsz: Int): ByteArray

        @JvmStatic
        @Throws(UnicornException::class)
        private external fun reg_write(handle: Long, regid: Int, value: ByteArray)

        @JvmStatic
        @Throws(UnicornException::class)
        private external fun reg_read(handle: Long, regid: Int): Long

        @JvmStatic
        @Throws(UnicornException::class)
        private external fun reg_write(handle: Long, regid: Int, value: Long)

        @JvmStatic
        private external fun register_emu_count_hook(handle: Long, emu_count: Long, hook: NewHook): Long

        @JvmStatic
        @Throws(UnicornException::class)
        private external fun mem_read(handle: Long, address: Long, size: Long): ByteArray

        @JvmStatic
        @Throws(UnicornException::class)
        private external fun mem_write(handle: Long, address: Long, bytes: ByteArray)

        @JvmStatic
        @Throws(UnicornException::class)
        private external fun mem_map(handle: Long, address: Long, size: Long, perms: Int)

        @JvmStatic
        @Throws(UnicornException::class)
        private external fun mem_protect(handle: Long, address: Long, size: Long, perms: Int)

        @JvmStatic
        @Throws(UnicornException::class)
        private external fun mem_unmap(handle: Long, address: Long, size: Long)

        @JvmStatic
        private external fun setFastDebug(handle: Long, fastDebug: Boolean)

        @JvmStatic
        private external fun setSingleStep(handle: Long, singleStep: Int)

        @JvmStatic
        private external fun addBreakPoint(handle: Long, address: Long)

        @JvmStatic
        private external fun removeBreakPoint(handle: Long, address: Long)

        /**
         * Hook registration helper for hook types that require two additional arguments.
         *
         * @param handle   Internal unicorn uc_engine* eng associated with hooking Unicorn object
         * @param type     UC_HOOK_* hook type
         * @return         Unicorn uch returned for registered hook function
         */
        @JvmStatic
        private external fun registerHook(handle: Long, type: Int, begin: Long, end: Long, hook: NewHook): Long

        /**
         * Hook registration helper for hook types that require no additional arguments.
         *
         * @param handle   Internal unicorn uc_engine* eng associated with hooking Unicorn object
         * @param type     UC_HOOK_* hook type
         * @return         Unicorn uch returned for registered hook function
         */
        @JvmStatic
        private external fun registerHook(handle: Long, type: Int, hook: NewHook): Long

        @JvmStatic
        private external fun registerDebugger(handle: Long, begin: Long, end: Long, hook: NewHook): Long

        @JvmStatic
        @Throws(UnicornException::class)
        private external fun emu_start(handle: Long, begin: Long, until: Long, timeout: Long, count: Long)

        @JvmStatic
        @Throws(UnicornException::class)
        private external fun emu_stop(handle: Long)

        @JvmStatic
        private external fun context_alloc(handle: Long): Long

        /**
         * Free a resource allocated within Unicorn. Use for handles
         * allocated by context_alloc.
         *
         * @param handle Previously allocated Unicorn object handle.
         */
        @JvmStatic
        external fun free(handle: Long)

        @JvmStatic
        private external fun context_save(handle: Long, context: Long)

        @JvmStatic
        private external fun context_restore(handle: Long, context: Long)

        @JvmStatic
        private external fun mem_allocated_size(handle: Long): Long

        @JvmStatic
        private external fun mem_resident_size(handle: Long): Long

        @JvmStatic
        external fun testSampleArm()

        @JvmStatic
        external fun testSampleArm64()

        @JvmStatic
        private external fun removeCache(handle: Long, begin: Long, end: Long)
    }

}
