package com.vortexdbg.hook.hookzz

import com.vortexdbg.Emulator
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import unicorn.ArmConst

import java.util.Stack

class HookZzArm32RegisterContextImpl internal constructor(private val emulator: Emulator<*>, context: Stack<Any?>) :
    HookZzRegisterContext(context), RegisterContext, HookZzArm32RegisterContext {

    private val reg_ctx: Pointer = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R0).share(8L) // skip dummy

    override fun getPointerArg(index: Int): VortexdbgPointer {
        if (index < 4) {
            when (index) {
                0 -> return getR0Pointer()
                1 -> return getR1Pointer()
                2 -> return getR2Pointer()
                3 -> return getR3Pointer()
                else -> throw IllegalArgumentException("index=$index")
            }
        }

        val sp = getStackPointer()
        return sp.getPointer((index - 4).toLong() * emulator.getPointerSize())
    }

    override fun setR0(r0: Int) {
        reg_ctx.setInt(0L, r0)
    }

    override fun setR1(r1: Int) {
        reg_ctx.setInt(4L, r1)
    }

    override fun setR2(r2: Int) {
        reg_ctx.setInt(8L, r2)
    }

    override fun setR3(r3: Int) {
        reg_ctx.setInt(12L, r3)
    }

    override fun setR4(r4: Int) {
        reg_ctx.setInt(16L, r4)
    }

    override fun setR5(r5: Int) {
        reg_ctx.setInt(20L, r5)
    }

    override fun setR6(r6: Int) {
        reg_ctx.setInt(24L, r6)
    }

    override fun setR7(r7: Int) {
        reg_ctx.setInt(28L, r7)
    }

    override fun setStackPointer(sp: Pointer) {
        throw UnsupportedOperationException()
    }

    override fun getR0Long(): Long {
        return reg_ctx.getInt(0L).toLong() and 0xffffffffL
    }

    override fun getR1Long(): Long {
        return reg_ctx.getInt(4L).toLong() and 0xffffffffL
    }

    override fun getR2Long(): Long {
        return reg_ctx.getInt(8L).toLong() and 0xffffffffL
    }

    override fun getR3Long(): Long {
        return reg_ctx.getInt(12L).toLong() and 0xffffffffL
    }

    override fun getR4Long(): Long {
        return reg_ctx.getInt(16L).toLong() and 0xffffffffL
    }

    override fun getR5Long(): Long {
        return reg_ctx.getInt(20L).toLong() and 0xffffffffL
    }

    override fun getR6Long(): Long {
        return reg_ctx.getInt(24L).toLong() and 0xffffffffL
    }

    override fun getR7Long(): Long {
        return reg_ctx.getInt(28L).toLong() and 0xffffffffL
    }

    override fun getR8Long(): Long {
        return reg_ctx.getInt(32L).toLong() and 0xffffffffL
    }

    override fun getR9Long(): Long {
        return reg_ctx.getInt(36L).toLong() and 0xffffffffL
    }

    override fun getR10Long(): Long {
        return reg_ctx.getInt(40L).toLong() and 0xffffffffL
    }

    override fun getR11Long(): Long {
        return reg_ctx.getInt(44L).toLong() and 0xffffffffL
    }

    override fun getR12Long(): Long {
        return reg_ctx.getInt(48L).toLong() and 0xffffffffL
    }

    override fun getLR(): Long {
        return reg_ctx.getInt(52L).toLong() and 0xffffffffL
    }

    override fun getR0Int(): Int {
        return getR0Long().toInt()
    }

    override fun getR1Int(): Int {
        return getR1Long().toInt()
    }

    override fun getR2Int(): Int {
        return getR2Long().toInt()
    }

    override fun getR3Int(): Int {
        return getR3Long().toInt()
    }

    override fun getR4Int(): Int {
        return getR4Long().toInt()
    }

    override fun getR5Int(): Int {
        return getR5Long().toInt()
    }

    override fun getR6Int(): Int {
        return getR6Long().toInt()
    }

    override fun getR7Int(): Int {
        return getR7Long().toInt()
    }

    override fun getR8Int(): Int {
        return getR8Long().toInt()
    }

    override fun getR9Int(): Int {
        return getR9Long().toInt()
    }

    override fun getR10Int(): Int {
        return getR10Long().toInt()
    }

    override fun getR11Int(): Int {
        return getR11Long().toInt()
    }

    override fun getR12Int(): Int {
        return getR12Long().toInt()
    }

    override fun getStackPointer(): VortexdbgPointer {
        return reg_ctx.share(56L) as VortexdbgPointer
    }

    override fun getR0Pointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getR0Long())
    }

    override fun getR1Pointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getR1Long())
    }

    override fun getR2Pointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getR2Long())
    }

    override fun getR3Pointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getR3Long())
    }

    override fun getR4Pointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getR4Long())
    }

    override fun getR5Pointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getR5Long())
    }

    override fun getR6Pointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getR6Long())
    }

    override fun getR7Pointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getR7Long())
    }

    override fun getR8Pointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getR8Long())
    }

    override fun getR9Pointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getR9Long())
    }

    override fun getR10Pointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getR10Long())
    }

    override fun getR11Pointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getR11Long())
    }

    override fun getR12Pointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getR12Long())
    }

    override fun getLRPointer(): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, getLR())
    }
}
