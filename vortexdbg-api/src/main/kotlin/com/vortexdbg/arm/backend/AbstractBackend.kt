package com.vortexdbg.arm.backend

import com.vortexdbg.Emulator
import com.vortexdbg.arm.ARM
import com.vortexdbg.arm.Cpsr
import com.vortexdbg.debugger.BreakPoint
import com.vortexdbg.debugger.BreakPointCallback
import com.vortexdbg.pointer.VortexdbgPointer
import unicorn.Arm64Const
import unicorn.ArmConst

abstract class AbstractBackend : Backend {

    protected open class BreakPointImpl(@JvmField val callback: BreakPointCallback?, @JvmField val thumb: Boolean) : BreakPoint {
        @JvmField
        var isTemporary: Boolean = false
        override fun setTemporary(temporary: Boolean) {
            this.isTemporary = true
        }
        override fun isTemporary(): Boolean {
            return isTemporary
        }
        override fun getCallback(): BreakPointCallback? {
            return callback
        }
        override fun isThumb(): Boolean {
            return thumb
        }
    }

    protected open fun switchUserMode(is64Bit: Boolean) {
        if (!is64Bit) {
            Cpsr.getArm(this).switchUserMode()
        }
    }

    protected open fun enableVFP(is64Bit: Boolean) {
        if (is64Bit) {
            var value = reg_read(Arm64Const.UC_ARM64_REG_CPACR_EL1).toLong()
            value = value or 0x300000 // set the FPEN bits
            reg_write(Arm64Const.UC_ARM64_REG_CPACR_EL1, value)
        } else {
            var value = reg_read(ArmConst.UC_ARM_REG_C1_C0_2).toInt()
            value = value or (0xf shl 20)
            reg_write(ArmConst.UC_ARM_REG_C1_C0_2, value)
            reg_write(ArmConst.UC_ARM_REG_FPEXC, 0x40000000)
        }
    }

    protected open fun checkVectorRegId(regId: Int, is64Bit: Boolean) {
        if (is64Bit) {
            if (regId < Arm64Const.UC_ARM64_REG_Q0 || regId > Arm64Const.UC_ARM64_REG_Q31) {
                throw UnsupportedOperationException("regId=$regId")
            }
        } else {
            if (regId < ArmConst.UC_ARM_REG_D0 || regId > ArmConst.UC_ARM_REG_D15) {
                throw UnsupportedOperationException("regId=$regId")
            }
        }
    }

    protected open fun decodeSWI(emulator: Emulator<*>, backend: Backend, is64Bit: Boolean): Int {
        if (is64Bit) {
            val pc = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_PC)
            return (pc.getInt(-4L) shr 5) and 0xffff
        } else {
            val pc = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_PC)
            val isThumb = ARM.isThumb(backend)
            return if (isThumb) {
                pc.getShort(-2L).toInt() and 0xff
            } else {
                pc.getInt(-4L) and 0xffffff
            }
        }
    }

    override fun onInitialize() {
    }

    override fun getPageSize(): Int {
        return 0
    }

    override fun registerEmuCountHook(emu_count: Long) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun removeJitCodeCache(begin: Long, end: Long) {
    }

    override fun getCpuFeatures(): @JvmSuppressWildcards Map<String, Int> {
        return emptyMap()
    }

    override fun getMemAllocatedSize(): Long {
        throw UnsupportedOperationException()
    }

    override fun getMemResidentSize(): Long {
        throw UnsupportedOperationException()
    }

}
