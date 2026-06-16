package com.vortexdbg.arm.backend.kvm

import com.vortexdbg.Emulator
import com.vortexdbg.arm.ARMEmulator
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.arm.backend.DebugHook
import com.vortexdbg.arm.backend.KvmBackend
import com.vortexdbg.pointer.VortexdbgPointer
import keystone.Keystone
import keystone.KeystoneArchitecture
import keystone.KeystoneEncoded
import keystone.KeystoneMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.ArmConst
import unicorn.UnicornConst

import java.nio.ByteBuffer
import java.nio.ByteOrder

class KvmBackend32(emulator: Emulator<*>, kvm: Kvm) : KvmBackend(emulator, kvm) {

    override fun onInitialize() {
        super.onInitialize()

        mem_map(KvmBackend.REG_VBAR_EL1, getPageSize().toLong(), UnicornConst.UC_PROT_READ or UnicornConst.UC_PROT_EXEC)
        val buffer = ByteBuffer.allocate(getPageSize())
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.hasRemaining()) {
            if (buffer.position() == 0x600) {
                buffer.putInt(0x390003e0) // strb w0, [sp]
            } else {
                buffer.putInt(0x390003e1) // strb w1, [sp]
            }
            if (buffer.hasRemaining()) {
                buffer.putInt(0xd69f03e0.toInt()) // eret
            }
        }
        val ptr = VortexdbgPointer.pointer(emulator, KvmBackend.REG_VBAR_EL1)
        assert(ptr != null)
        ptr!!.write(buffer.array())
    }

    override fun handleException(esr: Long, far: Long, elr: Long, spsr: Long, pc: Long): Boolean {
        val ec = ((esr shr 26) and 0x3fL).toInt()
        if (log.isDebugEnabled) {
            log.debug("handleException syndrome=0x{}, far=0x{}, elr=0x{}, ec=0x{}, pc=0x{}", java.lang.Long.toHexString(esr), java.lang.Long.toHexString(far), java.lang.Long.toHexString(elr), Integer.toHexString(ec), java.lang.Long.toHexString(pc))
        }

        when (ec) {
            EC_AA32_SVC -> {
                val swi = (esr and 0xffffL).toInt()
                callSVC(elr, swi)
                return true
            }
            EC_AA32_BKPT -> {
                val bkpt = (esr and 0xffffL).toInt()
                interruptHookNotifier!!.notifyCallSVC(this, ARMEmulator.EXCP_BKPT, bkpt)
                return true
            }
            else ->
                throw UnsupportedOperationException("handleException ec=0x" + Integer.toHexString(ec))
        }
    }

    override fun switchUserMode() {
    }

    override fun enableVFP() {
        enableVFP(false)
    }

    @Throws(BackendException::class)
    override fun reg_read(regId: Int): Number {
        try {
            when (regId) {
                ArmConst.UC_ARM_REG_R0,
                ArmConst.UC_ARM_REG_R1,
                ArmConst.UC_ARM_REG_R2,
                ArmConst.UC_ARM_REG_R3,
                ArmConst.UC_ARM_REG_R4,
                ArmConst.UC_ARM_REG_R5,
                ArmConst.UC_ARM_REG_R6,
                ArmConst.UC_ARM_REG_R7,
                ArmConst.UC_ARM_REG_R8,
                ArmConst.UC_ARM_REG_R9,
                ArmConst.UC_ARM_REG_R10,
                ArmConst.UC_ARM_REG_R11,
                ArmConst.UC_ARM_REG_R12 ->
                    return (kvm.reg_read64(regId - ArmConst.UC_ARM_REG_R0) and 0xffffffffL).toInt()
                ArmConst.UC_ARM_REG_SP ->
                    return (kvm.reg_read64(13) and 0xffffffffL).toInt()
                ArmConst.UC_ARM_REG_LR ->
                    return (kvm.reg_read64(14) and 0xffffffffL).toInt()
                ArmConst.UC_ARM_REG_PC ->
                    return (kvm.reg_read_pc64() and 0xffffffffL).toInt()
                ArmConst.UC_ARM_REG_CPSR ->
                    return kvm.reg_read_nzcv()
                ArmConst.UC_ARM_REG_C1_C0_2 ->
                    return kvm.reg_read_cpacr_el1()
                else ->
                    throw KvmException("regId=$regId")
            }
        } catch (e: KvmException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun reg_write(regId: Int, value: Number) {
        try {
            when (regId) {
                ArmConst.UC_ARM_REG_R0,
                ArmConst.UC_ARM_REG_R1,
                ArmConst.UC_ARM_REG_R2,
                ArmConst.UC_ARM_REG_R3,
                ArmConst.UC_ARM_REG_R4,
                ArmConst.UC_ARM_REG_R5,
                ArmConst.UC_ARM_REG_R6,
                ArmConst.UC_ARM_REG_R7,
                ArmConst.UC_ARM_REG_R8,
                ArmConst.UC_ARM_REG_R9,
                ArmConst.UC_ARM_REG_R10,
                ArmConst.UC_ARM_REG_R11,
                ArmConst.UC_ARM_REG_R12 ->
                    kvm.reg_write64(regId - ArmConst.UC_ARM_REG_R0, value.toLong() and 0xffffffffL)
                ArmConst.UC_ARM_REG_SP ->
                    kvm.reg_write64(13, value.toLong() and 0xffffffffL)
                ArmConst.UC_ARM_REG_LR ->
                    kvm.reg_write64(14, value.toLong() and 0xffffffffL)
                ArmConst.UC_ARM_REG_FPEXC ->
                    kvm.reg_set_fpexc(value.toLong() and 0xffffffffL)
                ArmConst.UC_ARM_REG_C13_C0_3 ->
                    kvm.reg_set_tpidrro_el0(value.toLong() and 0xffffffffL)
                ArmConst.UC_ARM_REG_C1_C0_2 ->
                    kvm.reg_set_cpacr_el1(value.toLong())
                ArmConst.UC_ARM_REG_CPSR ->
                    kvm.reg_set_nzcv(value.toLong() and 0xffffffffL)
                else ->
                    throw KvmException("regId=$regId")
            }
        } catch (e: KvmException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun reg_read_vector(regId: Int): ByteArray? {
        return null
    }

    @Throws(BackendException::class)
    override fun reg_write_vector(regId: Int, vector: ByteArray) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun debugger_add(callback: DebugHook, begin: Long, end: Long, user_data: Any?) {
    }

    override fun addSoftBreakPoint(address: Long, svcNumber: Int, thumb: Boolean): ByteArray {
        Keystone(KeystoneArchitecture.Arm, if (thumb) KeystoneMode.ArmThumb else KeystoneMode.Arm).use { keystone ->
            val encoded = keystone.assemble("bkpt #$svcNumber")
            return encoded.machineCode
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(KvmBackend32::class.java)

        private const val EC_AA32_SVC = 0x11
        private const val EC_AA32_BKPT = 0x38
    }

}
