package com.vortexdbg.arm.backend

import com.vortexdbg.debugger.BreakPoint
import com.vortexdbg.debugger.BreakPointCallback

interface Backend {

    /**
     * Called after the backend is constructed to perform any additional initialization,
     * such as mapping exception vector tables (hypervisor) or setting up initial CPU state.
     */
    fun onInitialize()

    /**
     * Switches the CPU to user mode (EL0/USR).
     * On AArch32 this updates the CPSR mode bits; on AArch64 backends this is typically a no-op
     * since only user-mode emulation is supported.
     */
    fun switchUserMode()

    /**
     * Enables the VFP (Vector Floating-Point) and Advanced SIMD (NEON) coprocessor.
     * On AArch64 this sets the FPEN bits in CPACR_EL1;
     * on AArch32 this configures C1_C0_2 and FPEXC registers.
     */
    fun enableVFP()

    /**
     * Reads the value of a CPU register.
     *
     * @param regId register ID, typically from {@code unicorn.ArmConst} or {@code unicorn.Arm64Const}
     * @return the register value as a {@link Number} (Integer for 32-bit, Long for 64-bit)
     * @throws BackendException if the register read fails
     */
    @Throws(BackendException::class)
    fun reg_read(regId: Int): Number

    /**
     * Reads a 128-bit SIMD/FP vector register.
     *
     * @param regId vector register ID (e.g., UC_ARM64_REG_Q0..Q31 or UC_ARM_REG_D0..D15)
     * @return 16-byte array containing the vector register value
     * @throws BackendException if the register read fails
     */
    @Throws(BackendException::class)
    fun reg_read_vector(regId: Int): ByteArray?

    /**
     * Writes a 128-bit SIMD/FP vector register.
     *
     * @param regId  vector register ID
     * @param vector 16-byte array containing the value to write
     * @throws BackendException if the register write fails
     */
    @Throws(BackendException::class)
    fun reg_write_vector(regId: Int, vector: ByteArray)

    /**
     * Writes a value to a CPU register.
     *
     * @param regId register ID
     * @param value the value to write (truncated to 32-bit for AArch32)
     * @throws BackendException if the register write fails
     */
    @Throws(BackendException::class)
    fun reg_write(regId: Int, value: Number)

    /**
     * Reads a range of bytes from emulated memory.
     *
     * @param address starting guest virtual address
     * @param size    number of bytes to read
     * @return byte array containing the memory contents
     * @throws BackendException if the address is unmapped or read fails
     */
    @Throws(BackendException::class)
    fun mem_read(address: Long, size: Long): ByteArray

    /**
     * Writes a byte array into emulated memory.
     *
     * @param address starting guest virtual address
     * @param bytes   data to write
     * @throws BackendException if the address is unmapped or write fails
     */
    @Throws(BackendException::class)
    fun mem_write(address: Long, bytes: ByteArray)

    /**
     * Maps a region of emulated memory. The address and size must be page-aligned.
     * The backing host memory is allocated lazily via {@code mmap(MAP_ANONYMOUS)}.
     *
     * @param address page-aligned starting guest virtual address
     * @param size    page-aligned size in bytes
     * @param perms   permissions bitmask (combination of UC_PROT_READ, UC_PROT_WRITE, UC_PROT_EXEC)
     * @throws BackendException if alignment is invalid or mapping fails
     */
    @Throws(BackendException::class)
    fun mem_map(address: Long, size: Long, perms: Int)

    /**
     * Changes the access permissions of an existing memory mapping.
     *
     * @param address page-aligned starting guest virtual address
     * @param size    page-aligned size in bytes
     * @param perms   new permissions bitmask
     * @throws BackendException if the region is not mapped or operation fails
     */
    @Throws(BackendException::class)
    fun mem_protect(address: Long, size: Long, perms: Int)

    /**
     * Unmaps a previously mapped memory region and releases the associated host memory.
     *
     * @param address page-aligned starting guest virtual address
     * @param size    page-aligned size in bytes
     * @throws BackendException if the region is not mapped or unmapping fails
     */
    @Throws(BackendException::class)
    fun mem_unmap(address: Long, size: Long)

    /**
     * Adds a software breakpoint at the given address.
     *
     * @param address  guest virtual address to break on
     * @param callback optional callback invoked when the breakpoint is hit, may be {@code null}
     * @param thumb    {@code true} if the breakpoint targets a Thumb instruction (AArch32 only)
     * @return a {@link BreakPoint} handle that can be used to query or modify the breakpoint
     */
    fun addBreakPoint(address: Long, callback: BreakPointCallback?, thumb: Boolean): BreakPoint

    /**
     * Removes a previously added breakpoint.
     *
     * @param address guest virtual address of the breakpoint to remove
     * @return {@code true} if the breakpoint was found and removed
     */
    fun removeBreakPoint(address: Long): Boolean

    /**
     * Configures single-step execution. After calling this, the emulator will
     * break after executing the specified number of instructions.
     *
     * @param singleStep number of instructions to execute before breaking; 0 to disable
     */
    fun setSingleStep(singleStep: Int)

    /**
     * Toggles fast debug mode. When enabled, the debugger hook skips per-instruction
     * callbacks and only breaks on breakpoints or single-step events, improving performance.
     *
     * @param fastDebug {@code true} to enable fast debug mode
     */
    fun setFastDebug(fastDebug: Boolean)

    /**
     * Invalidates the JIT code cache for the specified address range.
     * Should be called after modifying code in mapped memory to ensure
     * the backend re-translates the affected region.
     *
     * @param begin start address of the range (inclusive)
     * @param end   end address of the range (exclusive)
     * @throws BackendException if the operation fails
     */
    @Throws(BackendException::class)
    fun removeJitCodeCache(begin: Long, end: Long)

    /**
     * Registers a hook that is called for every instruction executed within the given address range.
     *
     * @param callback  the code hook callback
     * @param begin     start address of the hook range (inclusive)
     * @param end       end address of the hook range (inclusive)
     * @param user_data arbitrary user data passed to the callback
     * @throws BackendException if hook registration fails
     */
    @Throws(BackendException::class)
    fun hook_add_new(callback: CodeHook, begin: Long, end: Long, user_data: Any?)

    /**
     * Registers a debugger hook that handles breakpoints and single-step events
     * within the given address range.
     *
     * @param callback  the debug hook callback
     * @param begin     start address of the hook range (inclusive)
     * @param end       end address of the hook range (inclusive)
     * @param user_data arbitrary user data passed to the callback
     * @throws BackendException if hook registration fails
     */
    @Throws(BackendException::class)
    fun debugger_add(callback: DebugHook, begin: Long, end: Long, user_data: Any?)

    /**
     * Registers a hook for memory read events within the given address range.
     *
     * @param callback  the read hook callback
     * @param begin     start address of the hook range (inclusive)
     * @param end       end address of the hook range (inclusive)
     * @param user_data arbitrary user data passed to the callback
     * @throws BackendException if hook registration fails
     */
    @Throws(BackendException::class)
    fun hook_add_new(callback: ReadHook, begin: Long, end: Long, user_data: Any?)

    /**
     * Registers a hook for memory write events within the given address range.
     *
     * @param callback  the write hook callback
     * @param begin     start address of the hook range (inclusive)
     * @param end       end address of the hook range (inclusive)
     * @param user_data arbitrary user data passed to the callback
     * @throws BackendException if hook registration fails
     */
    @Throws(BackendException::class)
    fun hook_add_new(callback: WriteHook, begin: Long, end: Long, user_data: Any?)

    /**
     * Registers a hook for invalid memory access events (unmapped or protection violations).
     *
     * @param callback  the event memory hook callback
     * @param type      bitmask of event types (e.g., UC_HOOK_MEM_READ_UNMAPPED, UC_HOOK_MEM_WRITE_UNMAPPED)
     * @param user_data arbitrary user data passed to the callback
     * @throws BackendException if hook registration fails
     */
    @Throws(BackendException::class)
    fun hook_add_new(callback: EventMemHook, type: Int, user_data: Any?)

    /**
     * Registers a hook for CPU interrupt/exception events (SVC, HVC, etc.).
     *
     * @param callback  the interrupt hook callback
     * @param user_data arbitrary user data passed to the callback
     * @throws BackendException if hook registration fails
     */
    @Throws(BackendException::class)
    fun hook_add_new(callback: InterruptHook, user_data: Any?)

    /**
     * Registers a hook that is called at the beginning of each basic block
     * within the given address range.
     *
     * @param callback  the block hook callback
     * @param begin     start address of the hook range (inclusive)
     * @param end       end address of the hook range (inclusive)
     * @param user_data arbitrary user data passed to the callback
     * @throws BackendException if hook registration fails
     */
    @Throws(BackendException::class)
    fun hook_add_new(callback: BlockHook, begin: Long, end: Long, user_data: Any?)

    /**
     * Starts emulation of machine code.
     *
     * @param begin   guest virtual address where emulation starts
     * @param until   guest virtual address where emulation stops (the instruction at this address is NOT executed)
     * @param timeout maximum duration in microseconds; 0 for no limit
     * @param count   maximum number of instructions to emulate; 0 for no limit
     * @throws BackendException if emulation fails
     */
    @Throws(BackendException::class)
    fun emu_start(begin: Long, until: Long, timeout: Long, count: Long)

    /**
     * Stops the current emulation. Typically called from within a hook callback.
     *
     * @throws BackendException if the operation fails
     */
    @Throws(BackendException::class)
    fun emu_stop()

    /**
     * Releases all resources associated with this backend, including the underlying
     * engine, mapped memory, and registered hooks. The backend must not be used after this call.
     *
     * @throws BackendException if cleanup fails
     */
    @Throws(BackendException::class)
    fun destroy()

    /**
     * Restores the CPU context from a previously saved snapshot.
     *
     * @param context handle returned by {@link #context_alloc()}
     * @see #context_save(long)
     */
    fun context_restore(context: Long)

    /**
     * Saves the current CPU context (registers and internal metadata) into the given snapshot.
     *
     * @param context handle returned by {@link #context_alloc()}
     * @see #context_restore(long)
     */
    fun context_save(context: Long)

    /**
     * Allocates a new CPU context snapshot that can be used with
     * {@link #context_save(long)} and {@link #context_restore(long)}.
     *
     * @return an opaque handle representing the allocated context
     * @see #context_free(long)
     */
    fun context_alloc(): Long

    /**
     * Frees a previously allocated CPU context snapshot.
     *
     * @param context handle returned by {@link #context_alloc()}
     */
    fun context_free(context: Long)

    /**
     * Returns the memory page size used by this backend.
     * Typically 4KB (dynarmic/kvm) or 16KB (hypervisor on Apple Silicon).
     *
     * @return page size in bytes
     */
    fun getPageSize(): Int

    /**
     * Registers a hook that stops emulation after the specified number of instructions
     * have been executed. Used for instruction counting and preemptive scheduling.
     *
     * @param emu_count maximum number of instructions before the hook fires; must be &gt; 0
     */
    fun registerEmuCountHook(emu_count: Long)

    /**
     * Returns the CPU feature flags supported by the backend.
     * <p>
     * <a href="https://github.com/google/cpu_features/blob/main/src/impl_aarch64_macos_or_iphone.c">arm64_features</a>
     * hw.optional.floatingpoint
     * hw.optional.AdvSIMD
     * hw.optional.arm.AdvSIMD
     * hw.optional.arm.FEAT_AES
     * hw.optional.arm.FEAT_PMULL
     * hw.optional.arm.FEAT_SHA1
     * hw.optional.arm.FEAT_SHA256
     * hw.optional.armv8_crc32
     * hw.optional.arm.FEAT_LSE
     * hw.optional.arm.FEAT_FP16
     * hw.optional.arm.AdvSIMD_HPFPCvt
     * hw.optional.arm.FEAT_RDM
     * hw.optional.arm.FEAT_JSCVT
     * hw.optional.arm.FEAT_FCMA
     * hw.optional.arm.FEAT_LRCPC
     * hw.optional.arm.FEAT_DPB
     * hw.optional.arm.FEAT_SHA3
     * hw.optional.arm.FEAT_DotProd
     * hw.optional.arm.FEAT_SHA512
     * hw.optional.arm.FEAT_FHM
     * hw.optional.arm.FEAT_DIT
     * hw.optional.arm.FEAT_LSE2
     * hw.optional.arm.FEAT_FlagM
     * hw.optional.arm.FEAT_SSBS
     * hw.optional.arm.FEAT_SB
     * hw.optional.arm.FEAT_FlagM2
     * hw.optional.arm.FEAT_FRINTTS
     * hw.optional.arm.FEAT_I8MM
     * hw.optional.arm.FEAT_BF16
     * hw.optional.arm.FEAT_BTI
     *
     * @return a map of feature name to value (1 if supported, 0 otherwise)
     */
    fun getCpuFeatures(): @JvmSuppressWildcards Map<String, Int>

    /**
     * Returns the total virtual memory size allocated via {@link #mem_map(long, long, int)}.
     * <p>
     * This is the sum of all mapped pages tracked by the backend's native memory hash table.
     * For unicorn2, this is computed from {@code uc_mem_regions()}.
     *
     * @return total allocated memory in bytes
     */
    fun getMemAllocatedSize(): Long

    /**
     * Returns the actual physical memory resident in RAM.
     * <p>
     * On macOS/Linux, this uses {@code mincore()} to check which pages are actually
     * backed by physical memory, since {@code mmap(MAP_ANONYMOUS)} allocates lazily.
     * Only pages that have been read from or written to will be counted.
     * <p>
     * On Windows (dynarmic) and unicorn2 (QEMU-based), host memory pointers are not
     * accessible, so this returns the same value as {@link #getMemAllocatedSize()}.
     *
     * @return resident memory in bytes
     */
    fun getMemResidentSize(): Long

    /**
     * Returns whether this backend runs on Apple's Hypervisor.framework.
     * Hypervisor backends have hardware-accelerated execution but certain limitations
     * (e.g., only one VM instance per process, AArch64 only).
     *
     * @return {@code true} if this is a hypervisor backend
     */
    fun isHypervisor(): Boolean {
        return false
    }

}
