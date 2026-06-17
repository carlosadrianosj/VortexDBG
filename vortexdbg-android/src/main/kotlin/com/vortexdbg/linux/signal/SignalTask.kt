package com.vortexdbg.linux.signal

import com.vortexdbg.AbstractEmulator
import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.memory.MemoryBlock
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.signal.AbstractSignalTask
import com.vortexdbg.signal.SigSet
import com.vortexdbg.signal.SignalOps
import com.vortexdbg.signal.UnixSigSet
import com.sun.jna.Pointer
import unicorn.Arm64Const
import unicorn.ArmConst

class SignalTask(signum: Int, private val action: SigAction, sig_info: Pointer?) : AbstractSignalTask(signum) {

    constructor(signum: Int, action: SigAction) : this(signum, action, null)

    private var sig_info: Pointer? = sig_info
    private var stack: VortexdbgPointer? = null

    override fun callHandler(signalOps: SignalOps, emulator: AbstractEmulator<*>): Number {
        val sigSet: SigSet? = signalOps.getSigMaskSet()
        try {
            val sa_mask = action.getMask()
            if (sigSet == null) {
                val newSigSet: SigSet = UnixSigSet(sa_mask)
                signalOps.setSigMaskSet(newSigSet)
            } else {
                sigSet.blockSigSet(sa_mask)
            }
            if (isContextSaved()) {
                return continueRun(emulator, emulator.getReturnAddress())
            }
            return runHandler(emulator)
        } finally {
            if (sigSet != null) {
                signalOps.setSigMaskSet(sigSet)
            }
        }
    }

    private var ucontext: MemoryBlock? = null

    private fun runHandler(emulator: AbstractEmulator<*>): Number {
        val backend: Backend = emulator.getBackend()
        if (stack == null) {
            stack = allocateStack(emulator)
        }
        if (action.needSigInfo() && infoBlock == null && sig_info == null) {
            infoBlock = emulator.getMemory().malloc(128, true)
            infoBlock!!.getPointer().setInt(0L, signum)
            sig_info = infoBlock!!.getPointer()
        }
        if (ucontext == null) {
            ucontext = emulator.getMemory().malloc(0x1000, true)
        }
        if (emulator.is32Bit()) {
            backend.reg_write(ArmConst.UC_ARM_REG_SP, stack!!.peer)
            backend.reg_write(ArmConst.UC_ARM_REG_R0, signum)
            backend.reg_write(ArmConst.UC_ARM_REG_R1, VortexdbgPointer.nativeValueOf(sig_info)) // siginfo_t *info
            backend.reg_write(ArmConst.UC_ARM_REG_R2, VortexdbgPointer.nativeValueOf(ucontext!!.getPointer()))
            backend.reg_write(ArmConst.UC_ARM_REG_LR, emulator.getReturnAddress())
        } else {
            backend.reg_write(Arm64Const.UC_ARM64_REG_SP, stack!!.peer)
            backend.reg_write(Arm64Const.UC_ARM64_REG_X0, signum)
            backend.reg_write(Arm64Const.UC_ARM64_REG_X1, VortexdbgPointer.nativeValueOf(sig_info)) // siginfo_t *info
            backend.reg_write(Arm64Const.UC_ARM64_REG_X2, VortexdbgPointer.nativeValueOf(ucontext!!.getPointer()))
            backend.reg_write(Arm64Const.UC_ARM64_REG_LR, emulator.getReturnAddress())
        }
        return emulator.emulate(action.getSaHandler(), emulator.getReturnAddress())!!
    }

    override fun toThreadString(): String {
        return "SignalTask sa_handler=0x" + java.lang.Long.toHexString(action.getSaHandler()) + ", stack=" + stack + ", signum=" + signum
    }

    private var infoBlock: MemoryBlock? = null

    override fun destroy(emulator: Emulator<*>) {
        super.destroy(emulator)

        if (ucontext != null) {
            ucontext!!.free()
            ucontext = null
        }
        if (infoBlock != null) {
            infoBlock!!.free()
            infoBlock = null
        }
    }

}
