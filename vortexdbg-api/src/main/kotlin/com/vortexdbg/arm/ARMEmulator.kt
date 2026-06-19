package com.vortexdbg.arm

import com.vortexdbg.Emulator
import com.vortexdbg.file.NewFileIO

/**
 * Common contract for the 32-bit and 64-bit ARM emulators, exposing CPSR mode
 * constants and the ELF relocation type codes used by the dynamic loader.
 */
interface ARMEmulator<T : NewFileIO> : Emulator<T> {

    companion object {
        // Relocation type codes per the ARM ELF ABI (IHI0044F).

        /** CPSR mode bits for user mode (EL0/USR). */
        const val USR_MODE = 0b10000

        /** CPSR mode bits for supervisor mode (SVC). */
        const val SVC_MODE = 0b10011

        const val R_ARM_ABS32 = 2
        const val R_ARM_REL32 = 3
        const val R_ARM_COPY = 20
        const val R_ARM_GLOB_DAT = 21
        const val R_ARM_JUMP_SLOT = 22
        const val R_ARM_RELATIVE = 23
        const val R_ARM_IRELATIVE = 160

        const val R_AARCH64_ABS64 = 257
        const val R_AARCH64_ABS32 = 258
        const val R_AARCH64_ABS16 = 259
        const val R_AARCH64_PREL64 = 260
        const val R_AARCH64_PREL32 = 261
        const val R_AARCH64_PREL16 = 262
        const val R_AARCH64_COPY = 1024
        const val R_AARCH64_GLOB_DAT = 1025
        const val R_AARCH64_JUMP_SLOT = 1026
        const val R_AARCH64_RELATIVE = 1027
        const val R_AARCH64_TLS_TPREL64 = 1030
        const val R_AARCH64_TLS_DTPREL32 = 1031
        const val R_AARCH64_IRELATIVE = 1032

        const val PAGE_ALIGN = 0x1000 // 4k

        const val EXCP_UDEF = 1 /* undefined instruction */
        const val EXCP_SWI = 2 /* software interrupt */
        const val EXCP_BKPT = 7 /* software breakpoint */
    }

}
