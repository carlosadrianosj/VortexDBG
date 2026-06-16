package com.vortexdbg.arm.backend.dynarmic

import com.vortexdbg.Emulator
import com.vortexdbg.arm.ARMEmulator
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.arm.backend.DynarmicBackend
import keystone.Keystone
import keystone.KeystoneArchitecture
import keystone.KeystoneEncoded
import keystone.KeystoneMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.ArmConst

class DynarmicBackend32(emulator: Emulator<*>, dynarmic: Dynarmic) : DynarmicBackend(emulator, dynarmic) {

    override fun callSVC(pc: Long, swi: Int) {
        if (log.isDebugEnabled) {
            log.debug("callSVC pc=0x{}, swi={}", java.lang.Long.toHexString(pc), swi)
        }
        if (pc == until) {
            emu_stop()
            return
        }
        interruptHookNotifier.notifyCallSVC(this, ARMEmulator.EXCP_SWI, swi)
    }

    @Throws(BackendException::class)
    override fun reg_read(regId: Int): Number {
        try {
            return when (regId) {
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
                    dynarmic.reg_read32(regId - ArmConst.UC_ARM_REG_R0)
                ArmConst.UC_ARM_REG_SP ->
                    dynarmic.reg_read32(13)
                ArmConst.UC_ARM_REG_LR ->
                    dynarmic.reg_read32(14)
                ArmConst.UC_ARM_REG_PC ->
                    dynarmic.reg_read32(15)
                ArmConst.UC_ARM_REG_CPSR ->
                    dynarmic.reg_read_cpsr()
                else ->
                    throw DynarmicException("regId=$regId")
            }
        } catch (e: DynarmicException) {
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
                    dynarmic.reg_write32(regId - ArmConst.UC_ARM_REG_R0, value.toInt().toLong())
                ArmConst.UC_ARM_REG_SP ->
                    dynarmic.reg_write32(13, value.toInt().toLong())
                ArmConst.UC_ARM_REG_LR ->
                    dynarmic.reg_write32(14, value.toInt().toLong())
                ArmConst.UC_ARM_REG_C13_C0_3 ->
                    dynarmic.reg_write_c13_c0_3(value.toInt())
                ArmConst.UC_ARM_REG_CPSR ->
                    dynarmic.reg_write_cpsr(value.toInt())
                else ->
                    throw DynarmicException("regId=$regId")
            }
        } catch (e: DynarmicException) {
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

    override protected fun addSoftBreakPoint(address: Long, svcNumber: Int, thumb: Boolean): ByteArray {
        Keystone(KeystoneArchitecture.Arm, if (thumb) KeystoneMode.ArmThumb else KeystoneMode.Arm).use { keystone ->
            val encoded: KeystoneEncoded = keystone.assemble("bkpt #$svcNumber")
            return encoded.getMachineCode()
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(DynarmicBackend32::class.java)
    }
}
