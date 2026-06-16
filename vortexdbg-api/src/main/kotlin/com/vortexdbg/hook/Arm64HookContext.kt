package com.vortexdbg.hook

import com.vortexdbg.arm.context.EditableArm64RegisterContext
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import java.util.Stack

internal class Arm64HookContext(
    stack: Stack<Any?>,
    private val registerContext: EditableArm64RegisterContext
) : HookContext(stack), EditableArm64RegisterContext {

    override fun setXLong(index: Int, value: Long) {
        registerContext.setXLong(index, value)
    }

    override fun setStackPointer(sp: Pointer) {
        registerContext.setStackPointer(sp)
    }

    override fun getXLong(index: Int): Long {
        return registerContext.getXLong(index)
    }

    override fun getXInt(index: Int): Int {
        return registerContext.getXInt(index)
    }

    override fun getXPointer(index: Int): VortexdbgPointer {
        return registerContext.getXPointer(index)
    }

    override fun getFp(): Long {
        return registerContext.getFp()
    }

    override fun getFpPointer(): VortexdbgPointer {
        return registerContext.getFpPointer()
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
