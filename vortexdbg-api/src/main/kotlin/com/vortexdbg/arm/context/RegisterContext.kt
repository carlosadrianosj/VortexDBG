package com.vortexdbg.arm.context

import com.vortexdbg.pointer.VortexdbgPointer

interface RegisterContext {

    /**
     * @param index 0 based
     */
    fun getIntArg(index: Int): Int

    /**
     * @param index 0 based
     */
    fun getLongArg(index: Int): Long

    /**
     * @param index 0 based
     */
    fun getPointerArg(index: Int): VortexdbgPointer?

    fun getLR(): Long

    fun getLRPointer(): VortexdbgPointer

    fun getPCPointer(): VortexdbgPointer

    /**
     * sp
     */
    fun getStackPointer(): VortexdbgPointer

    fun getIntByReg(regId: Int): Int
    fun getLongByReg(regId: Int): Long

}
