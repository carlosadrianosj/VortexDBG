package com.vortexdbg.unwind

import com.vortexdbg.Emulator
import com.vortexdbg.arm.ARM
import com.vortexdbg.pointer.VortexdbgPointer
import unicorn.ArmConst

open class SimpleARMUnwinder(emulator: Emulator<*>) : Unwinder(emulator) {

    override fun getBaseFormat(): String {
        return "[0x%08x]"
    }

    override fun createFrame(ip: VortexdbgPointer?, fp: VortexdbgPointer?): Frame? {
        if (ip != null) {
            if (ip.peer == emulator.getReturnAddress()) {
                return Frame(ip, null)
            }

            return Frame(ARM.adjust_ip(ip), fp)
        } else {
            return null
        }
    }

    private fun initFrame(emulator: Emulator<*>): Frame? {
        val ip = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_LR)
        val fp = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R7)
        return createFrame(ip, fp)
    }

    override fun unw_step(emulator: Emulator<*>, frame: Frame?): Frame? {
        if (frame == null) {
            return initFrame(emulator)
        }

        val sp = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_SP)
        if (frame.fp == null || frame.fp.peer < sp.peer) {
            System.err.println("fp=" + frame.fp + ", sp=" + sp)
            return null
        }

        val ip = frame.fp.getPointer(4)
        val fp = frame.fp.getPointer(0)
        return createFrame(ip, fp)
    }

}
