package com.vortexdbg.arm.backend.dynarmic

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.Closeable

class Dynarmic(is64Bit: Boolean) : Closeable {

    private val nativeHandle: Long = nativeInitialize(is64Bit)

    fun context_alloc(): Long {
        return context_alloc(nativeHandle)
    }

    fun context_save(context: Long) {
        context_save(nativeHandle, context)
    }

    fun context_restore(context: Long) {
        context_restore(nativeHandle, context)
    }

    fun setDynarmicCallback(callback: DynarmicCallback) {
        if (log.isDebugEnabled) {
            log.debug("setDynarmicCallback callback={}", callback)
        }

        val ret = setDynarmicCallback(nativeHandle, callback)
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
        }
    }

    fun emu_start(begin: Long) {
        val ret = emu_start(nativeHandle, begin)
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
        }
    }

    fun emu_stop() {
        if (log.isDebugEnabled) {
            log.debug("emu_stop")
        }

        val ret = emu_stop(nativeHandle)
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
        }
    }

    fun mem_unmap(address: Long, size: Long) {
        val start = if (log.isDebugEnabled) System.currentTimeMillis() else 0
        val ret = mem_unmap(nativeHandle, address, size)
        if (log.isDebugEnabled) {
            log.debug("mem_unmap address=0x{}, size=0x{}, offset={}ms", java.lang.Long.toHexString(address), java.lang.Long.toHexString(size), System.currentTimeMillis() - start)
        }
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
        }
    }

    fun mem_map(address: Long, size: Long, perms: Int) {
        val start = if (log.isDebugEnabled) System.currentTimeMillis() else 0
        val ret = mem_map(nativeHandle, address, size, perms)
        if (log.isDebugEnabled) {
            log.debug("mem_map address=0x{}, size=0x{}, perms=0b{}, offset={}ms", java.lang.Long.toHexString(address), java.lang.Long.toHexString(size), Integer.toBinaryString(perms), System.currentTimeMillis() - start)
        }
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
        }
    }

    fun mem_protect(address: Long, size: Long, perms: Int) {
        val start = if (log.isDebugEnabled) System.currentTimeMillis() else 0
        val ret = mem_protect(nativeHandle, address, size, perms)
        if (log.isDebugEnabled) {
            log.debug("mem_protect address=0x{}, size=0x{}, perms=0b{}, offset={}ms", java.lang.Long.toHexString(address), java.lang.Long.toHexString(size), Integer.toBinaryString(perms), System.currentTimeMillis() - start)
        }
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
        }
    }

    fun reg_set_sp64(value: Long) {
        if (log.isDebugEnabled) {
            log.debug("reg_set_sp64 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_sp64(nativeHandle, value)
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
        }
    }

    fun reg_read_pc64(): Long {
        val pc = reg_read_pc64(nativeHandle)
        if (log.isDebugEnabled) {
            log.debug("reg_read_pc64=0x{}", java.lang.Long.toHexString(pc))
        }
        return pc
    }

    fun reg_read_sp64(): Long {
        val sp = reg_read_sp64(nativeHandle)
        if (log.isDebugEnabled) {
            log.debug("reg_read_sp64=0x{}", java.lang.Long.toHexString(sp))
        }
        return sp
    }

    fun reg_read_nzcv(): Long {
        val nzcv = reg_read_nzcv(nativeHandle)
        if (log.isDebugEnabled) {
            log.debug("reg_read_nzcv=0x{}", java.lang.Long.toHexString(nzcv))
        }
        return nzcv
    }

    fun reg_set_nzcv(value: Long) {
        if (log.isDebugEnabled) {
            log.debug("reg_set_nzcv value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_nzcv(nativeHandle, value)
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
        }
    }

    fun reg_set_tpidr_el0(value: Long) {
        if (log.isDebugEnabled) {
            log.debug("reg_set_tpidr_el0 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_tpidr_el0(nativeHandle, value)
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
        }
    }

    fun reg_set_tpidrro_el0(value: Long) {
        if (log.isDebugEnabled) {
            log.debug("reg_set_tpidrro_el0 value=0x{}", java.lang.Long.toHexString(value))
        }
        val ret = reg_set_tpidrro_el0(nativeHandle, value)
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
        }
    }

    fun reg_set_vector(index: Int, vector: ByteArray) {
        val ret = reg_set_vector(nativeHandle, index, vector)
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
        }
    }

    fun reg_read_vector(index: Int): ByteArray {
        val ret = reg_read_vector(nativeHandle, index)
            ?: throw DynarmicException("ret is null")
        return ret
    }

    fun reg_write32(index: Int, value: Long) {
        var value = value
        if (index < 0 || index > 15) {
            throw IllegalArgumentException("index=$index")
        }
        value = value and 0xffffffffL
        if (log.isDebugEnabled) {
            log.debug("reg_write32 index={}, value=0x{}", index, java.lang.Long.toHexString(value))
        }
        val ret = reg_write(nativeHandle, index, value)
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
        }
    }

    fun reg_write_c13_c0_3(value: Int) {
        if (log.isDebugEnabled) {
            log.debug("reg_write_c13_c0_3 value=0x{}", java.lang.Long.toHexString(value.toLong()))
        }
        val ret = reg_write_c13_c0_3(nativeHandle, value)
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
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
            throw DynarmicException("ret=$ret")
        }
    }

    fun reg_read32(index: Int): Int {
        if (index < 0 || index > 15) {
            throw IllegalArgumentException("index=$index")
        }
        if (log.isDebugEnabled) {
            log.debug("reg_read32 index={}", index)
        }
        return (reg_read(nativeHandle, index) and 0xffffffffL).toInt()
    }

    fun reg_read_cpsr(): Int {
        if (log.isDebugEnabled) {
            log.debug("reg_read_cpsr")
        }
        return reg_read_cpsr(nativeHandle)
    }

    fun reg_write_cpsr(value: Int) {
        if (log.isDebugEnabled) {
            log.debug("reg_write_cpsr value=0x{}", Integer.toHexString(value))
        }
        val ret = reg_write_cpsr(nativeHandle, value)
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
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

    fun mem_write(address: Long, bytes: ByteArray) {
        val start = if (log.isDebugEnabled) System.currentTimeMillis() else 0
        val ret = mem_write(nativeHandle, address, bytes)
        if (log.isDebugEnabled) {
            log.debug("mem_write address=0x{}, size={}, offset={}ms", java.lang.Long.toHexString(address), bytes.size, System.currentTimeMillis() - start)
        }
        if (ret != 0) {
            throw DynarmicException("ret=$ret")
        }
    }

    fun mem_read(address: Long, size: Int): ByteArray {
        val start = if (log.isDebugEnabled) System.currentTimeMillis() else 0
        val ret = mem_read(nativeHandle, address, size)
        if (log.isDebugEnabled) {
            log.debug("mem_read address=0x{}, size={}, offset={}ms", java.lang.Long.toHexString(address), size, System.currentTimeMillis() - start)
        }
        if (ret == null) {
            throw DynarmicException()
        }
        return ret
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

    companion object {

        private val log: Logger = LoggerFactory.getLogger(Dynarmic::class.java)

        @JvmStatic private external fun setDynarmicCallback(handle: Long, callback: DynarmicCallback): Int

        @JvmStatic private external fun nativeInitialize(is64Bit: Boolean): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)

        @JvmStatic private external fun mem_unmap(handle: Long, address: Long, size: Long): Int
        @JvmStatic private external fun mem_map(handle: Long, address: Long, size: Long, perms: Int): Int
        @JvmStatic private external fun mem_protect(handle: Long, address: Long, size: Long, perms: Int): Int

        @JvmStatic private external fun mem_write(handle: Long, address: Long, bytes: ByteArray): Int
        @JvmStatic private external fun mem_read(handle: Long, address: Long, size: Int): ByteArray?

        @JvmStatic private external fun reg_read_pc64(handle: Long): Long
        @JvmStatic private external fun reg_set_sp64(handle: Long, value: Long): Int
        @JvmStatic private external fun reg_read_sp64(handle: Long): Long
        @JvmStatic private external fun reg_read_nzcv(handle: Long): Long
        @JvmStatic private external fun reg_set_nzcv(handle: Long, value: Long): Int
        @JvmStatic private external fun reg_set_tpidr_el0(handle: Long, value: Long): Int
        @JvmStatic private external fun reg_set_tpidrro_el0(handle: Long, value: Long): Int
        @JvmStatic private external fun reg_set_vector(handle: Long, index: Int, vector: ByteArray): Int
        @JvmStatic private external fun reg_read_vector(handle: Long, index: Int): ByteArray?

        @JvmStatic private external fun reg_write(handle: Long, index: Int, value: Long): Int
        @JvmStatic private external fun reg_read(handle: Long, index: Int): Long
        @JvmStatic private external fun reg_read_cpsr(handle: Long): Int
        @JvmStatic private external fun reg_write_cpsr(handle: Long, value: Int): Int
        @JvmStatic private external fun reg_write_c13_c0_3(handle: Long, value: Int): Int

        @JvmStatic private external fun emu_start(handle: Long, pc: Long): Int
        @JvmStatic private external fun emu_stop(handle: Long): Int

        @JvmStatic private external fun context_alloc(handle: Long): Long
        @JvmStatic private external fun context_save(handle: Long, context: Long)
        @JvmStatic private external fun context_restore(handle: Long, context: Long)
        @JvmStatic external fun free(context: Long)

        @JvmStatic private external fun mem_allocated_size(handle: Long): Long
        @JvmStatic private external fun mem_resident_size(handle: Long): Long
    }

}
