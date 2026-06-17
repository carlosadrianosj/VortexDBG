package net.fornwall.jelf

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgPointer
import unicorn.Arm64Const

class DwarfCursor64(emulator: Emulator<*>) : DwarfCursor(arrayOfNulls(100)) {

    init {
        for (i in Arm64Const.UC_ARM64_REG_X0..Arm64Const.UC_ARM64_REG_X28) {
            val pointer = VortexdbgPointer.register(emulator, i)
            loc[i - Arm64Const.UC_ARM64_REG_X0] = if (pointer == null) 0L else pointer.peer
        }
        val x29 = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_FP)
        val x30 = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_LR)
        val x31 = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_SP)
        loc[FP] = if (x29 == null) 0L else x29.peer
        loc[LR] = if (x30 == null) 0L else x30.peer
        loc[SP] = if (x31 == null) 0L else x31.peer

        this.cfa = loc[SP]!!
        this.ip = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_PC)!!.peer
    }

    companion object {
        private const val FP = 29
        private const val LR = 30
        const val SP = 31
    }
}
