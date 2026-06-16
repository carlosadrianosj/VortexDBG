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
import unicorn.Arm64Const

class DynarmicBackend64(emulator: Emulator<*>, dynarmic: Dynarmic) : DynarmicBackend(emulator, dynarmic) {

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
                Arm64Const.UC_ARM64_REG_X0,
                Arm64Const.UC_ARM64_REG_X1,
                Arm64Const.UC_ARM64_REG_X2,
                Arm64Const.UC_ARM64_REG_X3,
                Arm64Const.UC_ARM64_REG_X4,
                Arm64Const.UC_ARM64_REG_X5,
                Arm64Const.UC_ARM64_REG_X6,
                Arm64Const.UC_ARM64_REG_X7,
                Arm64Const.UC_ARM64_REG_X8,
                Arm64Const.UC_ARM64_REG_X9,
                Arm64Const.UC_ARM64_REG_X10,
                Arm64Const.UC_ARM64_REG_X11,
                Arm64Const.UC_ARM64_REG_X12,
                Arm64Const.UC_ARM64_REG_X13,
                Arm64Const.UC_ARM64_REG_X14,
                Arm64Const.UC_ARM64_REG_X15,
                Arm64Const.UC_ARM64_REG_X16,
                Arm64Const.UC_ARM64_REG_X17,
                Arm64Const.UC_ARM64_REG_X18,
                Arm64Const.UC_ARM64_REG_X19,
                Arm64Const.UC_ARM64_REG_X20,
                Arm64Const.UC_ARM64_REG_X21,
                Arm64Const.UC_ARM64_REG_X22,
                Arm64Const.UC_ARM64_REG_X23,
                Arm64Const.UC_ARM64_REG_X24,
                Arm64Const.UC_ARM64_REG_X25,
                Arm64Const.UC_ARM64_REG_X26,
                Arm64Const.UC_ARM64_REG_X27,
                Arm64Const.UC_ARM64_REG_X28 ->
                    dynarmic.reg_read64(regId - Arm64Const.UC_ARM64_REG_X0)
                Arm64Const.UC_ARM64_REG_SP ->
                    dynarmic.reg_read_sp64()
                Arm64Const.UC_ARM64_REG_X29 ->
                    dynarmic.reg_read64(29)
                Arm64Const.UC_ARM64_REG_LR ->
                    dynarmic.reg_read64(30)
                Arm64Const.UC_ARM64_REG_PC ->
                    dynarmic.reg_read_pc64()
                Arm64Const.UC_ARM64_REG_NZCV ->
                    dynarmic.reg_read_nzcv()
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
                Arm64Const.UC_ARM64_REG_X0,
                Arm64Const.UC_ARM64_REG_X1,
                Arm64Const.UC_ARM64_REG_X2,
                Arm64Const.UC_ARM64_REG_X3,
                Arm64Const.UC_ARM64_REG_X4,
                Arm64Const.UC_ARM64_REG_X5,
                Arm64Const.UC_ARM64_REG_X6,
                Arm64Const.UC_ARM64_REG_X7,
                Arm64Const.UC_ARM64_REG_X8,
                Arm64Const.UC_ARM64_REG_X9,
                Arm64Const.UC_ARM64_REG_X10,
                Arm64Const.UC_ARM64_REG_X11,
                Arm64Const.UC_ARM64_REG_X12,
                Arm64Const.UC_ARM64_REG_X13,
                Arm64Const.UC_ARM64_REG_X14,
                Arm64Const.UC_ARM64_REG_X15,
                Arm64Const.UC_ARM64_REG_X16,
                Arm64Const.UC_ARM64_REG_X17,
                Arm64Const.UC_ARM64_REG_X18,
                Arm64Const.UC_ARM64_REG_X19,
                Arm64Const.UC_ARM64_REG_X20,
                Arm64Const.UC_ARM64_REG_X21,
                Arm64Const.UC_ARM64_REG_X22,
                Arm64Const.UC_ARM64_REG_X23,
                Arm64Const.UC_ARM64_REG_X24,
                Arm64Const.UC_ARM64_REG_X25,
                Arm64Const.UC_ARM64_REG_X26,
                Arm64Const.UC_ARM64_REG_X27,
                Arm64Const.UC_ARM64_REG_X28 ->
                    dynarmic.reg_write64(regId - Arm64Const.UC_ARM64_REG_X0, value.toLong())
                Arm64Const.UC_ARM64_REG_SP ->
                    dynarmic.reg_set_sp64(value.toLong())
                Arm64Const.UC_ARM64_REG_X29 ->
                    dynarmic.reg_write64(29, value.toLong())
                Arm64Const.UC_ARM64_REG_LR ->
                    dynarmic.reg_write64(30, value.toLong())
                Arm64Const.UC_ARM64_REG_TPIDR_EL0 ->
                    dynarmic.reg_set_tpidr_el0(value.toLong())
                Arm64Const.UC_ARM64_REG_TPIDRRO_EL0 ->
                    dynarmic.reg_set_tpidrro_el0(value.toLong())
                Arm64Const.UC_ARM64_REG_NZCV ->
                    dynarmic.reg_set_nzcv(value.toLong())
                else ->
                    throw DynarmicException("regId=$regId")
            }
        } catch (e: DynarmicException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun reg_read_vector(regId: Int): ByteArray? {
        try {
            if (regId >= Arm64Const.UC_ARM64_REG_Q0 && regId <= Arm64Const.UC_ARM64_REG_Q31) {
                return dynarmic.reg_read_vector(regId - Arm64Const.UC_ARM64_REG_Q0)
            } else {
                throw UnsupportedOperationException("regId=$regId")
            }
        } catch (e: DynarmicException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun reg_write_vector(regId: Int, vector: ByteArray) {
        try {
            if (vector.size != 16) {
                throw IllegalStateException("Invalid vector size")
            }

            if (regId >= Arm64Const.UC_ARM64_REG_Q0 && regId <= Arm64Const.UC_ARM64_REG_Q31) {
                dynarmic.reg_set_vector(regId - Arm64Const.UC_ARM64_REG_Q0, vector)
            } else {
                throw UnsupportedOperationException("regId=$regId")
            }
        } catch (e: DynarmicException) {
            throw BackendException(e)
        }
    }

    override protected fun addSoftBreakPoint(address: Long, svcNumber: Int, thumb: Boolean): ByteArray {
        Keystone(KeystoneArchitecture.Arm64, KeystoneMode.LittleEndian).use { keystone ->
            val encoded: KeystoneEncoded = keystone.assemble("brk #$svcNumber")
            return encoded.getMachineCode()
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(DynarmicBackend64::class.java)
    }
}
