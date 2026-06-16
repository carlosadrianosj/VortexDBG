package com.vortexdbg.arm.context

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.pointer.VortexdbgPointer

abstract class BaseRegisterContext internal constructor(
    protected val emulator: Emulator<*>,
    private val firstArgReg: Int,
    private val regArgCount: Int
) : AbstractRegisterContext(), RegisterContext {

    override fun getPointerArg(index: Int): VortexdbgPointer? {
        if (index < regArgCount) {
            val reg = firstArgReg + index
            return VortexdbgPointer.register(emulator, reg)
        }

        val sp = getStackPointer()
        return sp.getPointer((index - regArgCount).toLong() * emulator.getPointerSize())
    }

    override fun getIntByReg(regId: Int): Int {
        val backend: Backend = emulator.getBackend()
        val number: Number = backend.reg_read(regId)
        return number.toInt()
    }

    override fun getLongByReg(regId: Int): Long {
        val backend: Backend = emulator.getBackend()
        val number: Number = backend.reg_read(regId)
        return number.toLong()
    }

}
