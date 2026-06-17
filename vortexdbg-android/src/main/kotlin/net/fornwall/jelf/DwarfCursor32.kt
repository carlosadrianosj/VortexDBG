package net.fornwall.jelf

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgPointer
import unicorn.ArmConst

class DwarfCursor32(emulator: Emulator<*>) : DwarfCursor(arrayOfNulls(16)) {

    init {
        for (i in ArmConst.UC_ARM_REG_R0..ArmConst.UC_ARM_REG_R12) {
            val pointer = VortexdbgPointer.register(emulator, i)
            loc[i - ArmConst.UC_ARM_REG_R0] = if (pointer == null) 0L else pointer.peer
        }
        val r13 = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R13)
        val r14 = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R14)
        val r15 = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R15)
        loc[SP] = if (r13 == null) 0L else r13.peer
        loc[LR] = if (r14 == null) 0L else r14.peer
        loc[PC] = if (r15 == null) 0L else r15.peer

        this.cfa = loc[SP]!!
        this.ip = loc[PC]!!
    }

    companion object {
        const val SP = 13
        private const val LR = 14
        private const val PC = 15
    }
}
