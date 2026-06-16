package com.vortexdbg.arm.context

import com.sun.jna.Pointer

interface EditableArm64RegisterContext : Arm64RegisterContext {

    fun setXLong(index: Int, value: Long)

    fun setStackPointer(sp: Pointer)

}
