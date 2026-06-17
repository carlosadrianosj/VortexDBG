package com.vortexdbg.linux.signal

import com.vortexdbg.AbstractEmulator
import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.memory.MemoryBlock
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.thread.MainTask
import unicorn.Arm64Const
import unicorn.ArmConst

class SignalFunction(emulator: Emulator<*>, private val signum: Int, private val action: SigAction) :
    MainTask(emulator.getPid(), emulator.getReturnAddress()) {

    override fun getAddress(): Long {
        return action.getSaHandler()
    }

    override fun toThreadString(): String {
        return "SignalFunction sa_handler=" + action.getSaHandler() + ", signum=" + signum
    }

    private var infoBlock: MemoryBlock? = null

    override fun destroy(emulator: Emulator<*>) {
        super.destroy(emulator)

        if (infoBlock != null) {
            infoBlock!!.free()
            infoBlock = null
        }
    }

    override fun run(emulator: AbstractEmulator<*>): Number? {
        val backend: Backend = emulator.getBackend()
        if (action.needSigInfo() && infoBlock == null) {
            infoBlock = emulator.getMemory().malloc(128, true)
            infoBlock!!.getPointer().setInt(0L, signum)
        }
        val stack: VortexdbgPointer = allocateStack(emulator)
        if (emulator.is32Bit()) {
            backend.reg_write(ArmConst.UC_ARM_REG_SP, stack.peer)
            backend.reg_write(ArmConst.UC_ARM_REG_R0, signum)
            backend.reg_write(ArmConst.UC_ARM_REG_R1, if (infoBlock == null) 0 else infoBlock!!.getPointer().peer) // siginfo_t *info
            backend.reg_write(ArmConst.UC_ARM_REG_R2, 0) // void *ucontext
            backend.reg_write(ArmConst.UC_ARM_REG_LR, until)
        } else {
            backend.reg_write(Arm64Const.UC_ARM64_REG_SP, stack.peer)
            backend.reg_write(Arm64Const.UC_ARM64_REG_X0, signum)
            backend.reg_write(Arm64Const.UC_ARM64_REG_X1, if (infoBlock == null) 0 else infoBlock!!.getPointer().peer) // siginfo_t *info
            backend.reg_write(Arm64Const.UC_ARM64_REG_X2, 0) // void *ucontext
            backend.reg_write(Arm64Const.UC_ARM64_REG_LR, until)
        }
        return emulator.emulate(action.getSaHandler(), until)
    }

}
