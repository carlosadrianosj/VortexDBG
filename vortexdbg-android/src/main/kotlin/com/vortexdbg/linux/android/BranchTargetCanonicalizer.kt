package com.vortexdbg.linux.android

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.CodeHook
import com.vortexdbg.arm.backend.UnHook
import unicorn.Arm64Const

/**
 * Canonicalizes AArch64 indirect-branch targets whose high 32 bits are set.
 *
 * Some obfuscators (MBA / control-flow flattening) compute `br`/`blr` targets with sign-extended
 * 32-bit arithmetic. When the module is loaded below 4GB the low 32 bits hold the real target but the
 * high 32 bits come out as 0xffffffff, producing a non-canonical PC the CPU backend rejects on fetch.
 *
 * This installs a code hook over [begin, end) that, before each `br Xn` / `blr Xn` executes, masks the
 * target register to its low 32 bits when the high 32 bits are non-zero. The branch then lands on the
 * canonical address.
 *
 * Opt-in and only sound for a module known to live entirely in the low 4GB (any legitimate branch to a
 * genuinely high address would be masked too). A fetch-unmapped hook cannot fix this because unicorn
 * rejects the branch before invoking the fetch callback, so the register must be corrected pre-branch.
 */
object BranchTargetCanonicalizer {

    private const val BR = 0xD61F0000.toInt()   // br  Xn  (mask 0xFFFFFC1F)
    private const val BLR = 0xD63F0000.toInt()  // blr Xn
    private const val MASK = 0xFFFFFC1F.toInt()

    @JvmStatic
    fun install(emulator: Emulator<*>, begin: Long, end: Long) {
        emulator.getBackend().hook_add_new(object : CodeHook {
            override fun hook(backend: Backend, address: Long, size: Int, user: Any?) {
                val b = backend.mem_read(address, 4)
                val w = (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or
                        ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)
                val op = w and MASK
                if (op != BR && op != BLR) return
                val n = (w shr 5) and 0x1F
                if (n == 31) return   // xzr, never a branch target register in practice
                val reg = when {
                    n <= 28 -> Arm64Const.UC_ARM64_REG_X0 + n
                    n == 29 -> Arm64Const.UC_ARM64_REG_X29
                    else -> Arm64Const.UC_ARM64_REG_X30
                }
                val xn = backend.reg_read(reg).toLong()
                if ((xn ushr 32) != 0L) {
                    backend.reg_write(reg, xn and 0xFFFFFFFFL)
                }
            }

            override fun onAttach(unHook: UnHook) {}
            override fun detach() {}
        }, begin, end, null)
    }
}
