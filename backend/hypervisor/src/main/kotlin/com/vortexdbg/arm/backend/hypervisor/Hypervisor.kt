package com.vortexdbg.arm.backend.hypervisor

import com.sun.jna.Pointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.Closeable

class Hypervisor(is64Bit: Boolean) : Closeable {

    fun getCpuContextPointer(): Pointer {
        val peer = getCpuContext(nativeHandle)
        return if (peer == 0L) Pointer.NULL else Pointer(peer)
    }

    fun lookupVcpuPointer(): Pointer {
        val peer = lookupVcpu(nativeHandle)
        return if (peer == 0L) Pointer.NULL else Pointer(peer)
    }

    fun getBRPs(): Int {
        return getBRPs(nativeHandle)
    }

    fun getWRPs(): Int {
        return getWRPs(nativeHandle)
    }

    fun enable_single_step(status: Boolean) {
        if (log.isTraceEnabled) {
            log.trace("enable_single_step status={}", status)
        }
        enable_single_step(nativeHandle, status)
    }

    fun install_hw_breakpoint(n: Int, address: Long) {
        if (log.isTraceEnabled) {
            log.trace("install_hw_breakpoint n={}, address=0x{}", n, java.lang.Long.toHexString(address))
        }
        install_hw_breakpoint(nativeHandle, n, address)
    }

    fun install_hw_breakpoint_range(n: Int, begin: Long, end: Long) {
        if (log.isTraceEnabled) {
            log.trace("install_hw_breakpoint_range n={}, begin=0x{}, end=0x{}", n, java.lang.Long.toHexString(begin), java.lang.Long.toHexString(end))
        }
        install_hw_breakpoint_range(nativeHandle, n, begin, end)
    }

    fun get_page_perms(address: Long): Int {
        return get_page_perms(nativeHandle, address)
    }

    fun disable_hw_breakpoint(n: Int) {
        if (log.isTraceEnabled) {
            log.trace("disable_hw_breakpoint n={}", n)
        }
        disable_hw_breakpoint(nativeHandle, n)
    }

    fun install_watchpoint(n: Int, dbgwcr: Long, dbgwvr: Long) {
        install_watchpoint(nativeHandle, n, dbgwcr, dbgwvr)
        if (log.isDebugEnabled) {
            log.debug("install_watchpoint n={}, dbgwvr=0x{}, dbgwcr=0x{}", n, java.lang.Long.toHexString(dbgwvr), java.lang.Long.toHexString(dbgwcr))
        }
    }

    fun disable_watchpoint(n: Int) {
        install_watchpoint(nativeHandle, n, 0, 0)
        if (log.isDebugEnabled) {
            log.debug("disable_watchpoint n={}", n)
        }
    }

    private fun checkReturnCode(ret: Int) {
        if (ret != 0) {
            throw HypervisorException("ret=$ret")
        }
    }

    private val nativeHandle: Long

    init {
        if (is64Bit) {
            synchronized(Hypervisor::class.java) {
                if (singleInstance != null) {
                    throw IllegalStateException("Only one hypervisor VM instance per process allowed.")
                }
                this.nativeHandle = nativeInitialize(true)
                singleInstance = this
            }
        } else {
            throw UnsupportedOperationException()
        }
    }

    fun context_save(context: Long) {
        context_save(nativeHandle, context)
    }

    fun context_restore(context: Long) {
        context_restore(nativeHandle, context)
    }

    fun setHypervisorCallback(callback: HypervisorCallback) {
        if (log.isTraceEnabled) {
            log.trace("setHypervisorCallback callback={}", callback)
        }

        val ret = setHypervisorCallback(nativeHandle, callback)
        checkReturnCode(ret)
    }

    fun mem_map(address: Long, size: Long, perms: Int) {
        val start = if (log.isTraceEnabled) System.currentTimeMillis() else 0
        val ret = mem_map(nativeHandle, address, size, perms)
        if (log.isTraceEnabled) {
            log.trace("mem_map address=0x{}, size=0x{}, perms=0b{}, offset={}ms", java.lang.Long.toHexString(address), java.lang.Long.toHexString(size), Integer.toBinaryString(perms), System.currentTimeMillis() - start)
        }
        checkReturnCode(ret)
    }

    fun mem_protect(address: Long, size: Long, perms: Int) {
        val start = if (log.isTraceEnabled) System.currentTimeMillis() else 0
        val ret = mem_protect(nativeHandle, address, size, perms)
        if (log.isTraceEnabled) {
            log.trace("mem_protect address=0x{}, size=0x{}, perms=0b{}, offset={}ms", java.lang.Long.toHexString(address), java.lang.Long.toHexString(size), Integer.toBinaryString(perms), System.currentTimeMillis() - start)
        }
        checkReturnCode(ret)
    }

    fun mem_unmap(address: Long, size: Long) {
        val start = if (log.isTraceEnabled) System.currentTimeMillis() else 0
        val ret = mem_unmap(nativeHandle, address, size)
        if (log.isTraceEnabled) {
            log.trace("mem_unmap address=0x{}, size=0x{}, offset={}ms", java.lang.Long.toHexString(address), java.lang.Long.toHexString(size), System.currentTimeMillis() - start)
        }
        checkReturnCode(ret)
    }

    fun reg_write64(index: Int, value: Long) {
        if (index < 0 || index > 30) {
            throw IllegalArgumentException("index=$index")
        }
        if (log.isDebugEnabled) {
            log.debug("reg_write64 index={}, value=0x{}, pc=0x{}", index, java.lang.Long.toHexString(value), java.lang.Long.toHexString(reg_read_pc64()))
        }
        val ret = reg_write(nativeHandle, index, value)
        checkReturnCode(ret)
    }

    fun reg_set_sp64(value: Long) {
        if (log.isDebugEnabled) {
            log.debug("reg_set_sp64 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_sp64(nativeHandle, value)
        checkReturnCode(ret)
    }

    fun reg_set_tpidr_el0(value: Long) {
        if (log.isTraceEnabled) {
            log.trace("reg_set_tpidr_el0 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_tpidr_el0(nativeHandle, value)
        checkReturnCode(ret)
    }

    fun reg_set_tpidrro_el0(value: Long) {
        if (log.isTraceEnabled) {
            log.trace("reg_set_tpidrro_el0 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_tpidrro_el0(nativeHandle, value)
        checkReturnCode(ret)
    }

    /**
     * Sets the full SPSR_EL1, not just the NZCV bits.
     * Named "nzcv" for Unicorn UC_ARM64_REG_NZCV compatibility,
     * which reads/writes the entire CPSR/SPSR.
     */
    fun reg_set_nzcv(value: Long) {
        if (log.isTraceEnabled) {
            log.trace("reg_set_nzcv value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_nzcv(nativeHandle, value)
        checkReturnCode(ret)
    }

    fun reg_set_cpacr_el1(value: Long) {
        if (log.isTraceEnabled) {
            log.trace("reg_set_cpacr_el1 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_cpacr_el1(nativeHandle, value)
        checkReturnCode(ret)
    }

    fun reg_set_elr_el1(value: Long) {
        if (log.isTraceEnabled) {
            log.trace("reg_set_elr_el1 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_elr_el1(nativeHandle, value)
        checkReturnCode(ret)
    }

    /**
     * Sets HV_REG_PC directly. Required for advancing PC after handling
     * stage 2 direct VM exits (DATAABORT/INSNABORT) where ERET is not used.
     */
    fun reg_set_pc64(value: Long) {
        if (log.isTraceEnabled) {
            log.trace("reg_set_pc64 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_pc64(nativeHandle, value)
        checkReturnCode(ret)
    }

    fun reg_read_vector(index: Int): ByteArray {
        val ret = reg_read_vector(nativeHandle, index)
        if (ret == null) {
            throw HypervisorException()
        } else {
            return ret
        }
    }

    fun reg_set_vector(index: Int, vector: ByteArray) {
        val ret = reg_set_vector(nativeHandle, index, vector)
        checkReturnCode(ret)
    }

    fun reg_set_spsr_el1(value: Long) {
        if (log.isTraceEnabled) {
            log.trace("reg_set_spsr_el1 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_spsr_el1(nativeHandle, value)
        checkReturnCode(ret)
    }

    fun mem_write(address: Long, bytes: ByteArray) {
        val start = if (log.isTraceEnabled) System.currentTimeMillis() else 0
        val ret = mem_write(nativeHandle, address, bytes)
        if (log.isTraceEnabled) {
            log.trace("mem_write address=0x{}, size={}, offset={}ms", java.lang.Long.toHexString(address), bytes.size, System.currentTimeMillis() - start)
        }
        checkReturnCode(ret)
    }

    fun mem_read(address: Long, size: Int): ByteArray {
        val start = if (log.isTraceEnabled) System.currentTimeMillis() else 0
        val ret = mem_read(nativeHandle, address, size)
        if (log.isTraceEnabled) {
            log.trace("mem_read address=0x{}, size={}, offset={}ms", java.lang.Long.toHexString(address), size, System.currentTimeMillis() - start)
        }
        if (ret == null) {
            throw HypervisorException()
        }
        return ret
    }

    fun reg_read64(index: Int): Long {
        if (index < 0 || index > 30) {
            throw IllegalArgumentException("index=$index")
        }
        val value = reg_read(nativeHandle, index)
        if (log.isTraceEnabled) {
            log.trace("reg_read64 index={}, value=0x{}", index, java.lang.Long.toHexString(value))
        }
        return value
    }

    fun reg_read_sp64(): Long {
        val sp = reg_read_sp64(nativeHandle)
        if (log.isTraceEnabled) {
            log.trace("reg_read_sp64=0x{}", java.lang.Long.toHexString(sp))
        }
        return sp
    }

    fun reg_read_pc64(): Long {
        val pc = reg_read_pc64(nativeHandle)
        if (log.isTraceEnabled) {
            log.trace("reg_read_pc64=0x{}", java.lang.Long.toHexString(pc))
        }
        return pc
    }

    /**
     * Reads the full SPSR_EL1, not just the NZCV bits.
     * @see reg_set_nzcv
     */
    fun reg_read_nzcv(): Long {
        val nzcv = reg_read_nzcv(nativeHandle)
        if (log.isTraceEnabled) {
            log.trace("reg_read_nzcv=0x{}", java.lang.Long.toHexString(nzcv))
        }
        return nzcv
    }

    fun reg_read_cpacr_el1(): Long {
        val cpacr = reg_read_cpacr_el1(nativeHandle)
        if (log.isTraceEnabled) {
            log.trace("reg_read_cpacr_el1=0x{}", java.lang.Long.toHexString(cpacr))
        }
        return cpacr
    }

    fun emu_start(begin: Long) {
        val ret = emu_start(nativeHandle, begin)
        checkReturnCode(ret)
    }

    fun emu_stop() {
        if (log.isTraceEnabled) {
            log.trace("emu_stop")
        }

        val ret = emu_stop(nativeHandle)
        checkReturnCode(ret)
    }

    fun getMemAllocatedSize(): Long {
        return mem_allocated_size(nativeHandle)
    }

    fun getMemResidentSize(): Long {
        return mem_resident_size(nativeHandle)
    }

    private var closed: Boolean = false

    override fun close() {
        if (!closed) {
            synchronized(Hypervisor::class.java) {
                nativeDestroy(nativeHandle)
                singleInstance = null
                closed = true
            }
        }
    }

    companion object {

        private val log: Logger = LoggerFactory.getLogger(Hypervisor::class.java)

        const val REG_VBAR_EL1 = 0xf0000000L
        const val `PSTATE$SS` = (1 shl 21).toLong()

        @JvmStatic private external fun setHypervisorCallback(handle: Long, callback: HypervisorCallback): Int

        @JvmStatic private external fun nativeInitialize(is64Bit: Boolean): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)

        @JvmStatic private external fun mem_unmap(handle: Long, address: Long, size: Long): Int
        @JvmStatic private external fun mem_map(handle: Long, address: Long, size: Long, perms: Int): Int
        @JvmStatic private external fun mem_protect(handle: Long, address: Long, size: Long, perms: Int): Int

        @JvmStatic private external fun reg_write(handle: Long, index: Int, value: Long): Int
        @JvmStatic private external fun reg_set_sp64(handle: Long, value: Long): Int
        @JvmStatic private external fun reg_set_tpidr_el0(handle: Long, value: Long): Int
        @JvmStatic private external fun reg_set_tpidrro_el0(handle: Long, value: Long): Int
        @JvmStatic private external fun reg_set_nzcv(handle: Long, value: Long): Int
        @JvmStatic private external fun reg_set_cpacr_el1(handle: Long, value: Long): Int
        @JvmStatic private external fun reg_set_elr_el1(handle: Long, value: Long): Int
        @JvmStatic private external fun reg_set_pc64(handle: Long, value: Long): Int
        @JvmStatic private external fun reg_read_vector(handle: Long, index: Int): ByteArray?
        @JvmStatic private external fun reg_set_vector(handle: Long, index: Int, vector: ByteArray): Int
        @JvmStatic private external fun reg_set_spsr_el1(handle: Long, value: Long): Int

        @JvmStatic private external fun mem_write(handle: Long, address: Long, bytes: ByteArray): Int
        @JvmStatic private external fun mem_read(handle: Long, address: Long, size: Int): ByteArray?

        @JvmStatic private external fun reg_read(handle: Long, index: Int): Long
        @JvmStatic private external fun reg_read_sp64(handle: Long): Long
        @JvmStatic private external fun reg_read_pc64(handle: Long): Long
        @JvmStatic private external fun reg_read_nzcv(handle: Long): Long
        @JvmStatic private external fun reg_read_cpacr_el1(handle: Long): Long

        @JvmStatic private external fun emu_start(handle: Long, pc: Long): Int
        @JvmStatic private external fun emu_stop(handle: Long): Int

        @JvmStatic private external fun mem_allocated_size(handle: Long): Long
        @JvmStatic private external fun mem_resident_size(handle: Long): Long

        @JvmStatic private external fun context_save(handle: Long, context: Long)
        @JvmStatic private external fun context_restore(handle: Long, context: Long)

        @JvmStatic private external fun getBRPs(handle: Long): Int
        @JvmStatic private external fun getWRPs(handle: Long): Int

        @JvmStatic private external fun enable_single_step(handle: Long, status: Boolean)
        @JvmStatic private external fun install_hw_breakpoint(handle: Long, n: Int, address: Long)
        @JvmStatic private external fun install_hw_breakpoint_range(handle: Long, n: Int, begin: Long, end: Long)
        @JvmStatic private external fun get_page_perms(handle: Long, address: Long): Int
        @JvmStatic private external fun disable_hw_breakpoint(handle: Long, n: Int)
        @JvmStatic private external fun install_watchpoint(handle: Long, n: Int, dbgwcr: Long, dbgwvr: Long)

        @JvmStatic private external fun getCpuContext(handle: Long): Long // _hv_vcpu_get_context
        @JvmStatic private external fun lookupVcpu(handle: Long): Long
        @JvmStatic private external fun getVCpus(): Long // find_vcpus

        @JvmStatic private var singleInstance: Hypervisor? = null

        @JvmStatic
        fun getVCpusPointer(): Pointer {
            val peer = getVCpus()
            return if (peer == 0L) Pointer.NULL else Pointer(peer)
        }
    }

}
