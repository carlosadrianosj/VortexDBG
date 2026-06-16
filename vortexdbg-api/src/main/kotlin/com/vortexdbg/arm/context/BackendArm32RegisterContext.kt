package com.vortexdbg.arm.context

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import unicorn.ArmConst

class BackendArm32RegisterContext(
    private val backend: Backend,
    emulator: Emulator<*>
) : BaseRegisterContext(emulator, ArmConst.UC_ARM_REG_R0, 4), EditableArm32RegisterContext {

    private fun reg(regId: Int): Long {
        return backend.reg_read(regId).toInt().toLong() and 0xffffffffL
    }

    private fun set(regId: Int, value: Int) {
        backend.reg_write(regId, value)
    }

    override fun getR0Long(): Long {
        return reg(ArmConst.UC_ARM_REG_R0)
    }

    override fun getR1Long(): Long {
        return reg(ArmConst.UC_ARM_REG_R1)
    }

    override fun getR2Long(): Long {
        return reg(ArmConst.UC_ARM_REG_R2)
    }

    override fun getR3Long(): Long {
        return reg(ArmConst.UC_ARM_REG_R3)
    }

    override fun getR4Long(): Long {
        return reg(ArmConst.UC_ARM_REG_R4)
    }

    override fun getR5Long(): Long {
        return reg(ArmConst.UC_ARM_REG_R5)
    }

    override fun getR6Long(): Long {
        return reg(ArmConst.UC_ARM_REG_R6)
    }

    override fun getR7Long(): Long {
        return reg(ArmConst.UC_ARM_REG_R7)
    }

    override fun getR8Long(): Long {
        return reg(ArmConst.UC_ARM_REG_R8)
    }

    override fun getR9Long(): Long {
        return reg(ArmConst.UC_ARM_REG_R9)
    }

    override fun getR10Long(): Long {
        return reg(ArmConst.UC_ARM_REG_R10)
    }

    override fun getR11Long(): Long {
        return reg(ArmConst.UC_ARM_REG_R11)
    }

    override fun getR12Long(): Long {
        return reg(ArmConst.UC_ARM_REG_R12)
    }

    override fun setR0(r0: Int) {
        set(ArmConst.UC_ARM_REG_R0, r0)
    }

    override fun setR1(r1: Int) {
        set(ArmConst.UC_ARM_REG_R1, r1)
    }

    override fun setR2(r2: Int) {
        set(ArmConst.UC_ARM_REG_R2, r2)
    }

    override fun setR3(r3: Int) {
        set(ArmConst.UC_ARM_REG_R3, r3)
    }

    override fun setR4(r4: Int) {
        set(ArmConst.UC_ARM_REG_R4, r4)
    }

    override fun setR5(r5: Int) {
        set(ArmConst.UC_ARM_REG_R5, r5)
    }

    override fun setR6(r6: Int) {
        set(ArmConst.UC_ARM_REG_R6, r6)
    }

    override fun setR7(r7: Int) {
        set(ArmConst.UC_ARM_REG_R7, r7)
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

    override fun getPCPointer(): VortexdbgPointer {
        return VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_PC)
    }

    override fun getLR(): Long {
        return reg(ArmConst.UC_ARM_REG_LR)
    }

    override fun getStackPointer(): VortexdbgPointer {
        return VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_SP)
    }

    override fun setStackPointer(sp: Pointer) {
        backend.reg_write(ArmConst.UC_ARM_REG_SP, (sp as VortexdbgPointer).peer)
    }
}
