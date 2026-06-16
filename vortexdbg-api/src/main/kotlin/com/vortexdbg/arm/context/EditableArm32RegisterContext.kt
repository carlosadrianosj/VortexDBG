package com.vortexdbg.arm.context

import com.sun.jna.Pointer

interface EditableArm32RegisterContext : Arm32RegisterContext {

    fun setR0(r0: Int)

    fun setR1(r1: Int)

    fun setR2(r2: Int)

    fun setR3(r3: Int)

    fun setR4(r4: Int)

    fun setR5(r5: Int)

    fun setR6(r6: Int)

    fun setR7(r7: Int)

    fun setStackPointer(sp: Pointer)

}
