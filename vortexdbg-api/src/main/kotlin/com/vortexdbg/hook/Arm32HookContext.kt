package com.vortexdbg.hook

import com.vortexdbg.arm.context.EditableArm32RegisterContext
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import java.util.Stack

internal class Arm32HookContext(
    stack: Stack<Any?>,
    private val registerContext: EditableArm32RegisterContext
) : HookContext(stack), EditableArm32RegisterContext {

    override fun setR0(r0: Int) {
        registerContext.setR0(r0)
    }

    override fun setR1(r1: Int) {
        registerContext.setR1(r1)
    }

    override fun setR2(r2: Int) {
        registerContext.setR2(r2)
    }

    override fun setR3(r3: Int) {
        registerContext.setR3(r3)
    }

    override fun setR4(r4: Int) {
        registerContext.setR4(r4)
    }

    override fun setR5(r5: Int) {
        registerContext.setR5(r5)
    }

    override fun setR6(r6: Int) {
        registerContext.setR6(r6)
    }

    override fun setR7(r7: Int) {
        registerContext.setR7(r7)
    }

    override fun setStackPointer(sp: Pointer) {
        registerContext.setStackPointer(sp)
    }

    override fun getR0Long(): Long {
        return registerContext.getR0Long()
    }

    override fun getR1Long(): Long {
        return registerContext.getR1Long()
    }

    override fun getR2Long(): Long {
        return registerContext.getR2Long()
    }

    override fun getR3Long(): Long {
        return registerContext.getR3Long()
    }

    override fun getR4Long(): Long {
        return registerContext.getR4Long()
    }

    override fun getR5Long(): Long {
        return registerContext.getR5Long()
    }

    override fun getR6Long(): Long {
        return registerContext.getR6Long()
    }

    override fun getR7Long(): Long {
        return registerContext.getR7Long()
    }

    override fun getR8Long(): Long {
        return registerContext.getR8Long()
    }

    override fun getR9Long(): Long {
        return registerContext.getR9Long()
    }

    override fun getR10Long(): Long {
        return registerContext.getR10Long()
    }

    override fun getR11Long(): Long {
        return registerContext.getR11Long()
    }

    override fun getR12Long(): Long {
        return registerContext.getR12Long()
    }

    override fun getR0Int(): Int {
        return registerContext.getR0Int()
    }

    override fun getR1Int(): Int {
        return registerContext.getR1Int()
    }

    override fun getR2Int(): Int {
        return registerContext.getR2Int()
    }

    override fun getR3Int(): Int {
        return registerContext.getR3Int()
    }

    override fun getR4Int(): Int {
        return registerContext.getR4Int()
    }

    override fun getR5Int(): Int {
        return registerContext.getR5Int()
    }

    override fun getR6Int(): Int {
        return registerContext.getR6Int()
    }

    override fun getR7Int(): Int {
        return registerContext.getR7Int()
    }

    override fun getR8Int(): Int {
        return registerContext.getR8Int()
    }

    override fun getR9Int(): Int {
        return registerContext.getR9Int()
    }

    override fun getR10Int(): Int {
        return registerContext.getR10Int()
    }

    override fun getR11Int(): Int {
        return registerContext.getR11Int()
    }

    override fun getR12Int(): Int {
        return registerContext.getR12Int()
    }

    override fun getR0Pointer(): VortexdbgPointer {
        return registerContext.getR0Pointer()
    }

    override fun getR1Pointer(): VortexdbgPointer {
        return registerContext.getR1Pointer()
    }

    override fun getR2Pointer(): VortexdbgPointer {
        return registerContext.getR2Pointer()
    }

    override fun getR3Pointer(): VortexdbgPointer {
        return registerContext.getR3Pointer()
    }

    override fun getR4Pointer(): VortexdbgPointer {
        return registerContext.getR4Pointer()
    }

    override fun getR5Pointer(): VortexdbgPointer {
        return registerContext.getR5Pointer()
    }

    override fun getR6Pointer(): VortexdbgPointer {
        return registerContext.getR6Pointer()
    }

    override fun getR7Pointer(): VortexdbgPointer {
        return registerContext.getR7Pointer()
    }

    override fun getR8Pointer(): VortexdbgPointer {
        return registerContext.getR8Pointer()
    }

    override fun getR9Pointer(): VortexdbgPointer {
        return registerContext.getR9Pointer()
    }

    override fun getR10Pointer(): VortexdbgPointer {
        return registerContext.getR10Pointer()
    }

    override fun getR11Pointer(): VortexdbgPointer {
        return registerContext.getR11Pointer()
    }

    override fun getR12Pointer(): VortexdbgPointer {
        return registerContext.getR12Pointer()
    }

    override fun getIntArg(index: Int): Int {
        return registerContext.getIntArg(index)
    }

    override fun getLongArg(index: Int): Long {
        return registerContext.getLongArg(index)
    }

    override fun getPointerArg(index: Int): VortexdbgPointer? {
        return registerContext.getPointerArg(index)
    }

    override fun getLR(): Long {
        return registerContext.getLR()
    }

    override fun getLRPointer(): VortexdbgPointer {
        return registerContext.getLRPointer()
    }

    override fun getPCPointer(): VortexdbgPointer {
        return registerContext.getPCPointer()
    }

    override fun getStackPointer(): VortexdbgPointer {
        return registerContext.getStackPointer()
    }

    override fun getIntByReg(regId: Int): Int {
        return registerContext.getIntByReg(regId)
    }

    override fun getLongByReg(regId: Int): Long {
        return registerContext.getLongByReg(regId)
    }
}
