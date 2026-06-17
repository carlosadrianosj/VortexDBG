package com.vortexdbg.hook.hookzz

import com.vortexdbg.Emulator
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import unicorn.Arm64Const

import java.util.Stack

class HookZzArm64RegisterContextImpl internal constructor(private val emulator: Emulator<*>, context: Stack<Any?>) :
    HookZzRegisterContext(context), HookZzArm64RegisterContext {

    private val reg_ctx: Pointer = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_X0).share(8L) // skip dummy

    override fun getPointerArg(index: Int): VortexdbgPointer {
        if (index < 8) {
            return getXPointer(index)
        }

        val sp = getStackPointer()
        return sp.getPointer((index - 8).toLong() * emulator.getPointerSize())
    }

    override fun getXLong(index: Int): Long {
        if (index in 0..28) {
            return reg_ctx.getLong((index * 8).toLong())
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
        return reg_ctx.getLong((29 * 8).toLong())
    }

    override fun setXLong(index: Int, value: Long) {
        if (index in 0..28) {
            reg_ctx.setLong((index * 8).toLong(), value)
        } else {
            throw IllegalArgumentException("invalid index: $index")
        }
    }

    override fun setStackPointer(sp: Pointer) {
        throw UnsupportedOperationException()
    }

    override fun getFpPointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getFp())
    }

    override fun getLR(): Long {
        return reg_ctx.getLong((30 * 8).toLong())
    }

    override fun getLRPointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getLR())
    }

    override fun getStackPointer(): VortexdbgPointer {
        return reg_ctx.share((30 * 8 + 8 + 16 * 8).toLong()) as VortexdbgPointer
    }
}
