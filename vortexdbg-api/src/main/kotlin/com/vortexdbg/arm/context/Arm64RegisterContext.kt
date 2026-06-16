package com.vortexdbg.arm.context

import com.vortexdbg.pointer.VortexdbgPointer

interface Arm64RegisterContext : RegisterContext {

    fun getXLong(index: Int): Long

    fun getXInt(index: Int): Int

    fun getXPointer(index: Int): VortexdbgPointer

    fun getFp(): Long

    fun getFpPointer(): VortexdbgPointer

}
