package com.vortexdbg.hook.hookzz

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import unicorn.ArmConst

class ArmHookEntryInfo internal constructor(emulator: Emulator<*>) : HookEntryInfo {

    private val info: Pointer = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R1)

    override fun getHookId(): Long {
        return info.getInt(0L).toLong() and 0xffffffffL
    }

    override fun getAddress(): Long {
        return info.getInt(4L).toLong() and 0xffffffffL
    }
}
