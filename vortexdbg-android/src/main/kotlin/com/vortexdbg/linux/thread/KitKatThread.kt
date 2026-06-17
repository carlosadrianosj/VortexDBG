package com.vortexdbg.linux.thread

import com.vortexdbg.AbstractEmulator
import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.Symbol
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.thread.ThreadTask
import com.sun.jna.Pointer
import unicorn.ArmConst

class KitKatThread(
    tid: Int,
    until: Long,
    private val child_stack: VortexdbgPointer,
    private val fn: VortexdbgPointer,
    private val arg: VortexdbgPointer?
) : ThreadTask(tid, until) {

    private var errno: Pointer? = null

    override fun setErrno(emulator: Emulator<*>, errno: Int): Boolean {
        if (this.errno != null) {
            this.errno!!.setInt(0L, errno)
            return true
        }
        return super.setErrno(emulator, errno)
    }

    override fun toThreadString(): String {
        return "KitKatThread fn=$fn, arg=$arg, child_stack=$child_stack"
    }

    override fun runThread(emulator: AbstractEmulator<*>): Number? {
        val backend = emulator.getBackend()
        val stack = allocateStack(emulator)
        backend.reg_write(ArmConst.UC_ARM_REG_SP, stack.peer)
        this.errno = child_stack.share(8L)

        backend.reg_write(ArmConst.UC_ARM_REG_R0, this.fn.peer)
        backend.reg_write(ArmConst.UC_ARM_REG_R1, if (this.arg == null) 0L else this.arg.peer)
        backend.reg_write(ArmConst.UC_ARM_REG_R2, this.child_stack.peer)
        backend.reg_write(ArmConst.UC_ARM_REG_LR, until)

        val libc = emulator.getMemory().findModule("libc.so")
        val __thread_entry = libc!!.findSymbolByName("__thread_entry", false)
            ?: throw IllegalStateException()
        return emulator.emulate(__thread_entry.getAddress(), until)
    }

}
