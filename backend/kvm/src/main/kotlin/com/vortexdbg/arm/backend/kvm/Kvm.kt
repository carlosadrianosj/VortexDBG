package com.vortexdbg.arm.backend.kvm

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.Closeable

class Kvm(is64Bit: Boolean) : Closeable {

    private val nativeHandle: Long = nativeInitialize(is64Bit)

    fun setKvmCallback(callback: KvmCallback) {
        if (log.isDebugEnabled) {
            log.debug("setKvmCallback callback{}", callback)
        }

        val ret = setKvmCallback(nativeHandle, callback)
        if (ret != 0) {
            throw KvmException("ret=$ret")
        }
    }

    fun set_user_memory_region(slot: Int, guest_phys_addr: Long, memory_size: Long, old_addr: Long): Long {
        val userspace_addr = set_user_memory_region(nativeHandle, slot, guest_phys_addr, memory_size, old_addr)
        if (userspace_addr == 0L) {
            throw KvmException("set_user_memory_region failed: slot=" + slot + ", guest_phys_addr=0x" + java.lang.Long.toHexString(guest_phys_addr) + ", memory_size=0x" + java.lang.Long.toHexString(memory_size) + ", old_addr=0x" + java.lang.Long.toHexString(old_addr))
        }
        return userspace_addr
    }

    fun remove_user_memory_region(slot: Int, guest_phys_addr: Long, memory_size: Long, userspace_addr: Long, vaddr_off: Long) {
        val ret = remove_user_memory_region(nativeHandle, slot, guest_phys_addr, memory_size, userspace_addr, vaddr_off)
        if (ret != 0) {
            throw KvmException("remove_user_memory_region failed: slot=" + slot + ", guest_phys_addr=0x" + java.lang.Long.toHexString(guest_phys_addr) + ", memory_size=0x" + java.lang.Long.toHexString(memory_size) + ", userspace_addr=0x" + java.lang.Long.toHexString(userspace_addr) + ", vaddr_off=0x" + java.lang.Long.toHexString(vaddr_off))
        }
    }

    fun reg_read_cpacr_el1(): Long {
        val cpacr = reg_read_cpacr_el1(nativeHandle)
        if (log.isDebugEnabled) {
            log.debug("reg_read_cpacr_el1=0x{}", java.lang.Long.toHexString(cpacr))
        }
        return cpacr
    }

    fun reg_set_cpacr_el1(value: Long) {
        if (log.isDebugEnabled) {
            log.debug("reg_set_cpacr_el1 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_cpacr_el1(nativeHandle, value)
        if (ret != 0) {
            throw KvmException("ret=$ret")
        }
    }

    fun reg_set_sp64(value: Long) {
        if (log.isDebugEnabled) {
            log.debug("reg_set_sp64 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_sp64(nativeHandle, value)
        if (ret != 0) {
            throw KvmException("ret=$ret")
        }
    }

    fun reg_set_fpexc(value: Long) {
        if (log.isDebugEnabled) {
            log.debug("reg_set_fpexc value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_fpexc(nativeHandle, value)
        if (ret != 0) {
            throw KvmException("ret=$ret")
        }
    }

    fun reg_set_elr_el1(value: Long) {
        if (log.isDebugEnabled) {
            log.debug("reg_set_elr_el1 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_elr_el1(nativeHandle, value)
        if (ret != 0) {
            throw KvmException("ret=$ret")
        }
    }

    fun reg_read_sp64(): Long {
        val sp = reg_read_sp64(nativeHandle)
        if (log.isDebugEnabled) {
            log.debug("reg_read_sp64=0x{}", java.lang.Long.toHexString(sp))
        }
        return sp
    }

    fun reg_read_pc64(): Long {
        val pc = reg_read_pc64(nativeHandle)
        if (log.isDebugEnabled) {
            log.debug("reg_read_pc64=0x{}", java.lang.Long.toHexString(pc))
        }
        return pc
    }

    fun reg_read_nzcv(): Long {
        val nzcv = reg_read_nzcv(nativeHandle)
        if (log.isDebugEnabled) {
            log.debug("reg_read_nzcv=0x{}", java.lang.Long.toHexString(nzcv))
        }
        return nzcv
    }

    fun getMaxSlots(): Int {
        val ret = getMaxSlots(nativeHandle)
        if (ret <= 0)
            throw KvmException("getMaxSlots failed: ret=$ret")
        return ret
    }

    fun mem_write(address: Long, bytes: ByteArray) {
        val start = if (log.isDebugEnabled) System.currentTimeMillis() else 0
        val ret = mem_write(nativeHandle, address, bytes)
        if (log.isDebugEnabled) {
            log.debug("mem_write address=0x{}, size={}, offset={}ms", java.lang.Long.toHexString(address), bytes.size, System.currentTimeMillis() - start)
        }
        if (ret != 0) {
            throw KvmException("ret=$ret")
        }
    }

    fun mem_read(address: Long, size: Int): ByteArray {
        val start = if (log.isDebugEnabled) System.currentTimeMillis() else 0
        val ret = mem_read(nativeHandle, address, size)
        if (log.isDebugEnabled) {
            log.debug("mem_read address=0x{}, size={}, offset={}ms", java.lang.Long.toHexString(address), size, System.currentTimeMillis() - start)
        }
        if (ret == null) {
            throw KvmException()
        }
        return ret
    }

    fun reg_set_tpidr_el0(value: Long) {
        if (log.isDebugEnabled) {
            log.debug("reg_set_tpidr_el0 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_tpidr_el0(nativeHandle, value)
        if (ret != 0) {
            throw KvmException("ret=$ret")
        }
    }

    fun reg_set_tpidrro_el0(value: Long) {
        if (log.isDebugEnabled) {
            log.debug("reg_set_tpidrro_el0 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_tpidrro_el0(nativeHandle, value)
        if (ret != 0) {
            throw KvmException("ret=$ret")
        }
    }

    fun reg_set_nzcv(value: Long) {
        if (log.isDebugEnabled) {
            log.debug("reg_set_nzcv value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_nzcv(nativeHandle, value)
        if (ret != 0) {
            throw KvmException("ret=$ret")
        }
    }

    fun reg_write64(index: Int, value: Long) {
        if (index < 0 || index > 30) {
            throw IllegalArgumentException("index=$index")
        }
        if (log.isDebugEnabled) {
            log.debug("reg_write64 index={}, value=0x{}", index, java.lang.Long.toHexString(value))
        }
        val ret = reg_write(nativeHandle, index, value)
        if (ret != 0) {
            throw KvmException("ret=$ret")
        }
    }

    fun reg_read64(index: Int): Long {
        if (index < 0 || index > 30) {
            throw IllegalArgumentException("index=$index")
        }
        if (log.isDebugEnabled) {
            log.debug("reg_read64 index={}", index)
        }
        return reg_read(nativeHandle, index)
    }

    fun emu_start(begin: Long) {
        val ret = emu_start(nativeHandle, begin)
        if (ret != 0) {
            throw KvmException("ret=$ret")
        }
    }

    fun emu_stop() {
        if (log.isDebugEnabled) {
            log.debug("emu_stop")
        }

        val ret = emu_stop(nativeHandle)
        if (ret != 0) {
            throw KvmException("ret=$ret")
        }
    }

    fun getMemAllocatedSize(): Long {
        return mem_allocated_size(nativeHandle)
    }

    fun getMemResidentSize(): Long {
        return mem_resident_size(nativeHandle)
    }

    override fun close() {
        nativeDestroy(nativeHandle)
    }

    fun context_alloc(): Long {
        return context_alloc(nativeHandle)
    }

    fun context_save(context: Long) {
        context_save(nativeHandle, context)
    }

    fun context_restore(context: Long) {
        context_restore(nativeHandle, context)
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(Kvm::class.java)

        @JvmStatic
        private external fun setKvmCallback(handle: Long, callback: KvmCallback): Int

        @JvmStatic
        external fun getMaxSlots(handle: Long): Int

        @JvmStatic
        external fun getPageSize(): Int

        @JvmStatic
        private external fun nativeInitialize(is64Bit: Boolean): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun set_user_memory_region(handle: Long, slot: Int, guest_phys_addr: Long, memory_size: Long, userspace_addr: Long): Long

        @JvmStatic
        private external fun remove_user_memory_region(handle: Long, slot: Int, guest_phys_addr: Long, memory_size: Long, userspace_addr: Long, vaddr_off: Long): Int

        @JvmStatic
        private external fun reg_read_cpacr_el1(handle: Long): Long

        @JvmStatic
        private external fun reg_set_cpacr_el1(handle: Long, value: Long): Int

        @JvmStatic
        private external fun reg_set_fpexc(handle: Long, value: Long): Int

        @JvmStatic
        private external fun reg_set_sp64(handle: Long, value: Long): Int

        @JvmStatic
        private external fun reg_read_sp64(handle: Long): Long

        @JvmStatic
        private external fun reg_set_tpidr_el0(handle: Long, value: Long): Int

        @JvmStatic
        private external fun reg_set_tpidrro_el0(handle: Long, value: Long): Int

        @JvmStatic
        private external fun reg_set_nzcv(handle: Long, value: Long): Int

        @JvmStatic
        private external fun reg_set_elr_el1(handle: Long, value: Long): Int

        @JvmStatic
        private external fun reg_read_pc64(handle: Long): Long

        @JvmStatic
        private external fun reg_read_nzcv(handle: Long): Long

        @JvmStatic
        private external fun mem_write(handle: Long, address: Long, bytes: ByteArray): Int

        @JvmStatic
        private external fun mem_read(handle: Long, address: Long, size: Int): ByteArray?

        @JvmStatic
        private external fun reg_write(handle: Long, index: Int, value: Long): Int

        @JvmStatic
        private external fun reg_read(handle: Long, index: Int): Long

        @JvmStatic
        private external fun emu_start(handle: Long, pc: Long): Int

        @JvmStatic
        private external fun emu_stop(handle: Long): Int

        @JvmStatic
        external fun free(context: Long)

        @JvmStatic
        private external fun context_alloc(handle: Long): Long

        @JvmStatic
        private external fun context_save(handle: Long, context: Long)

        @JvmStatic
        private external fun context_restore(handle: Long, context: Long)

        @JvmStatic
        private external fun mem_allocated_size(handle: Long): Long

        @JvmStatic
        private external fun mem_resident_size(handle: Long): Long
    }
}
