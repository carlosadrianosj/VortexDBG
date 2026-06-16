package com.vortexdbg.arm.backend

import com.alibaba.fastjson.util.IOUtils
import com.vortexdbg.Emulator
import com.vortexdbg.arm.ARMEmulator
import com.vortexdbg.arm.backend.hypervisor.Hypervisor
import com.vortexdbg.arm.backend.hypervisor.HypervisorCallback
import com.vortexdbg.arm.backend.hypervisor.HypervisorException
import com.vortexdbg.pointer.VortexdbgPointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const
import unicorn.UnicornConst

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Objects

abstract class HypervisorBackend protected constructor(emulator: Emulator<*>, protected val hypervisor: Hypervisor) :
    FastBackend(emulator), Backend, HypervisorCallback {

    private val pageSize: Int

    init {
        this.pageSize = HypervisorFactory.getPageSize()
        try {
            this.hypervisor.setHypervisorCallback(this)
        } catch (e: HypervisorException) {
            throw BackendException(e)
        }
    }

    override fun onInitialize() {
        super.onInitialize()

        mem_map(Hypervisor.REG_VBAR_EL1, getPageSize().toLong(), UnicornConst.UC_PROT_READ or UnicornConst.UC_PROT_EXEC)
        val buffer = ByteBuffer.allocate(getPageSize())
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.hasRemaining()) {
            if (buffer.position() == 0x400) {
                buffer.putInt(0xd4000002.toInt()) // hvc #0
                buffer.putInt(0xd69f03e0.toInt()) // eret
                continue
            }
            buffer.putInt(0xd4201100.toInt()) // brk #0x88
        }
        val ptr = Objects.requireNonNull(VortexdbgPointer.pointer(emulator, Hypervisor.REG_VBAR_EL1))
        ptr.write(buffer.array())
    }

    override fun reg_read_vector(regId: Int): ByteArray {
        try {
            if (regId >= Arm64Const.UC_ARM64_REG_Q0 && regId <= Arm64Const.UC_ARM64_REG_Q31) {
                return hypervisor.reg_read_vector(regId - Arm64Const.UC_ARM64_REG_Q0)
            } else {
                throw UnsupportedOperationException("regId=$regId")
            }
        } catch (e: HypervisorException) {
            throw BackendException(e)
        }
    }

    override fun reg_write_vector(regId: Int, vector: ByteArray) {
        try {
            if (vector.size != 16) {
                throw IllegalStateException("Invalid vector size")
            }

            if (regId >= Arm64Const.UC_ARM64_REG_Q0 && regId <= Arm64Const.UC_ARM64_REG_Q31) {
                hypervisor.reg_set_vector(regId - Arm64Const.UC_ARM64_REG_Q0, vector)
            } else {
                throw UnsupportedOperationException("regId=$regId")
            }
        } catch (e: HypervisorException) {
            throw BackendException(e)
        }
    }

    override fun mem_read(address: Long, size: Long): ByteArray {
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw IllegalArgumentException("invalid size: $size")
        }
        try {
            return hypervisor.mem_read(address, size.toInt())
        } catch (e: HypervisorException) {
            throw BackendException(e)
        }
    }

    override fun mem_write(address: Long, bytes: ByteArray) {
        try {
            hypervisor.mem_write(address, bytes)
        } catch (e: HypervisorException) {
            throw BackendException(e)
        }
    }

    override fun mem_map(address: Long, size: Long, perms: Int) {
        try {
            hypervisor.mem_map(address, size, perms)
        } catch (e: HypervisorException) {
            throw BackendException(e)
        }
    }

    override fun mem_protect(address: Long, size: Long, perms: Int) {
        try {
            hypervisor.mem_protect(address, size, perms)
        } catch (e: HypervisorException) {
            throw BackendException(e)
        }
    }

    override fun mem_unmap(address: Long, size: Long) {
        try {
            hypervisor.mem_unmap(address, size)
        } catch (e: HypervisorException) {
            throw BackendException(e)
        }
    }

    protected inner class EventMemHookNotifier(
        private val callback: EventMemHook,
        private val type: Int,
        private val user: Any?
    ) {
        fun notifyDataAbort(isWrite: Boolean, size: Int, address: Long) {
            if (isWrite) {
                if ((type and UnicornConst.UC_HOOK_MEM_WRITE_UNMAPPED) != 0) {
                    callback.hook(this@HypervisorBackend, address, size, 0L, user, EventMemHook.UnmappedType.Write)
                }
            } else {
                if ((type and UnicornConst.UC_HOOK_MEM_READ_UNMAPPED) != 0) {
                    callback.hook(this@HypervisorBackend, address, size, 0L, user, EventMemHook.UnmappedType.Read)
                }
            }
        }

        fun notifyInsnAbort(address: Long) {
            if ((type and UnicornConst.UC_HOOK_MEM_FETCH_UNMAPPED) != 0) {
                callback.hook(this@HypervisorBackend, address, 4, 0L, user, EventMemHook.UnmappedType.Fetch)
            }
        }
    }

    protected var eventMemHookNotifier: EventMemHookNotifier? = null

    override fun hook_add_new(callback: EventMemHook, type: Int, userData: Any?) {
        if (eventMemHookNotifier != null) {
            throw IllegalStateException()
        }
        eventMemHookNotifier = EventMemHookNotifier(callback, type, userData)
    }

    protected var interruptHookNotifier: InterruptHookNotifier? = null

    override fun hook_add_new(callback: InterruptHook, userData: Any?) {
        if (interruptHookNotifier != null) {
            throw IllegalStateException()
        } else {
            interruptHookNotifier = InterruptHookNotifier(callback, userData)
        }
    }

    protected fun notifyInterruptHook(intno: Int, swi: Int) {
        if (log.isDebugEnabled) {
            log.debug("notifyInterruptHook intno={}, swi={}", intno, swi)
        }
        if (interruptHookNotifier == null) {
            throw IllegalStateException("interruptHookNotifier is null, bindInterruptHook not called before exception intno=$intno, swi=$swi")
        }
        interruptHookNotifier!!.notifyCallSVC(this, intno, swi)
    }

    protected fun callSVC(pc: Long, swi: Int) {
        if (log.isDebugEnabled) {
            log.debug("callSVC pc=0x{}, until=0x{}, swi={}", java.lang.Long.toHexString(pc), java.lang.Long.toHexString(until), swi)
        }
        if (pc == until) {
            emu_stop()
            return
        }
        notifyInterruptHook(ARMEmulator.EXCP_SWI, swi)
    }

    override fun hook_add_new(callback: BlockHook, begin: Long, end: Long, userData: Any?) {
        throw UnsupportedOperationException()
    }

    protected var until: Long = 0

    @Synchronized
    override fun emu_start(begin: Long, until: Long, timeout: Long, count: Long) {
        if (log.isDebugEnabled) {
            log.debug("emu_start begin=0x{}, until=0x{}, timeout={}, count={}", java.lang.Long.toHexString(begin), java.lang.Long.toHexString(until), timeout, count)
        }
        this.until = until + INS_SIZE
        try {
            hypervisor.emu_start(begin)
        } catch (e: HypervisorException) {
            throw BackendException(e)
        }
    }

    override fun emu_stop() {
        try {
            hypervisor.emu_stop()
        } catch (e: HypervisorException) {
            throw BackendException(e)
        }
    }

    override fun destroy() {
        IOUtils.close(hypervisor)
    }

    override fun getPageSize(): Int {
        return pageSize
    }

    override fun getMemAllocatedSize(): Long {
        return hypervisor.getMemAllocatedSize()
    }

    override fun getMemResidentSize(): Long {
        return hypervisor.getMemResidentSize()
    }

    override fun isHypervisor(): Boolean {
        return true
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(HypervisorBackend::class.java)

        @JvmStatic
        protected val INS_SIZE = 4
    }
}
