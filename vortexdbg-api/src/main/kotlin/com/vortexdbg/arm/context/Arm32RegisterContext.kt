package com.vortexdbg.arm.context

import com.vortexdbg.pointer.VortexdbgPointer

interface Arm32RegisterContext : RegisterContext {

    fun getR0Long(): Long

    fun getR1Long(): Long

    fun getR2Long(): Long

    fun getR3Long(): Long

    fun getR4Long(): Long

    fun getR5Long(): Long

    fun getR6Long(): Long

    fun getR7Long(): Long

    fun getR8Long(): Long

    fun getR9Long(): Long

    fun getR10Long(): Long

    fun getR11Long(): Long

    fun getR12Long(): Long

    fun getR0Int(): Int

    fun getR1Int(): Int

    fun getR2Int(): Int

    fun getR3Int(): Int

    fun getR4Int(): Int

    fun getR5Int(): Int

    fun getR6Int(): Int

    fun getR7Int(): Int

    fun getR8Int(): Int

    fun getR9Int(): Int

    fun getR10Int(): Int

    fun getR11Int(): Int

    fun getR12Int(): Int

    fun getR0Pointer(): VortexdbgPointer

    fun getR1Pointer(): VortexdbgPointer

    fun getR2Pointer(): VortexdbgPointer

    fun getR3Pointer(): VortexdbgPointer

    fun getR4Pointer(): VortexdbgPointer

    fun getR5Pointer(): VortexdbgPointer

    fun getR6Pointer(): VortexdbgPointer

    fun getR7Pointer(): VortexdbgPointer

    fun getR8Pointer(): VortexdbgPointer

    fun getR9Pointer(): VortexdbgPointer

    fun getR10Pointer(): VortexdbgPointer

    fun getR11Pointer(): VortexdbgPointer

    fun getR12Pointer(): VortexdbgPointer

}
