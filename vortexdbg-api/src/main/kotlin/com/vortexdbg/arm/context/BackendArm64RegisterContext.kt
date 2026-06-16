package com.vortexdbg.arm.context

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import unicorn.Arm64Const

class BackendArm64RegisterContext(
    private val backend: Backend,
    emulator: Emulator<*>
) : BaseRegisterContext(emulator, Arm64Const.UC_ARM64_REG_X0, 8), EditableArm64RegisterContext {

    private fun reg(regId: Int): Long {
        return backend.reg_read(regId).toLong()
    }

    override fun setXLong(index: Int, value: Long) {
        if (index in 0..28) {
            backend.reg_write(Arm64Const.UC_ARM64_REG_X0 + index, value)
            return
        }
        throw IllegalArgumentException("invalid index: $index")
    }

    override fun getXLong(index: Int): Long {
        if (index in 0..28) {
            return reg(Arm64Const.UC_ARM64_REG_X0 + index)
        }
        throw IllegalArgumentException("invalid index: $index")
    }

    override fun getXInt(index: Int): Int {
        return getXLong(index).toInt()
    }

    override fun getXPointer(index: Int): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getXLong(index))
    }

    override fun getFp(): Long {
        return reg(Arm64Const.UC_ARM64_REG_FP)
    }

    override fun getFpPointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getFp())
    }

    override fun getLR(): Long {
        return reg(Arm64Const.UC_ARM64_REG_LR)
    }

    override fun getLRPointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getLR())
    }

    override fun getPCPointer(): VortexdbgPointer {
        return VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_PC)
    }

    override fun getStackPointer(): VortexdbgPointer {
        return VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_SP)
    }

    override fun setStackPointer(sp: Pointer) {
        backend.reg_write(Arm64Const.UC_ARM64_REG_SP, (sp as VortexdbgPointer).peer)
    }
}
