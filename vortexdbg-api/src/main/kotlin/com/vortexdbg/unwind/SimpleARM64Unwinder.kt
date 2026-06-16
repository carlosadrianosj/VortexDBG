package com.vortexdbg.unwind

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgPointer
import unicorn.Arm64Const

open class SimpleARM64Unwinder(emulator: Emulator<*>) : Unwinder(emulator) {

    override fun getBaseFormat(): String {
        return "[0x%09x]"
    }

    override fun createFrame(ip: VortexdbgPointer?, fp: VortexdbgPointer?): Frame? {
        if (ip != null) {
            if (ip.peer == emulator.getReturnAddress()) {
                return Frame(ip, null)
            }

            val adjusted = ip.share(-4, 0)
            return Frame(adjusted, fp)
        } else {
            return null
        }
    }

    private fun initFrame(emulator: Emulator<*>): Frame? {
        val ip = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_LR)
        val fp = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_FP)
        return createFrame(ip, fp)
    }

    override fun unw_step(emulator: Emulator<*>, frame: Frame?): Frame? {
        if (frame == null) {
            return initFrame(emulator)
        }

        if (frame.fp == null) {
            System.err.println("fp is null")
            return null
        }

        val ip = frame.fp.getPointer(8)
        val fp = frame.fp.getPointer(0)
        return createFrame(ip, fp)
    }

}
