package com.vortexdbg.hook.hookzz

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import unicorn.Arm64Const

class Arm64HookEntryInfo internal constructor(emulator: Emulator<*>) : HookEntryInfo {

    private val info: Pointer = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_X1)

    override fun getHookId(): Long {
        return info.getLong(0L)
    }

    override fun getAddress(): Long {
        return info.getLong(8L)
    }
}
