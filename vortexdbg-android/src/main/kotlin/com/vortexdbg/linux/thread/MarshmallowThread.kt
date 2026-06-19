package com.vortexdbg.linux.thread

import com.vortexdbg.AbstractEmulator
import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.thread.ThreadTask
import com.sun.jna.Pointer
import unicorn.Arm64Const
import unicorn.ArmConst

class MarshmallowThread(
    emulator: Emulator<*>,
    private val fn: VortexdbgPointer,
    private val thread: VortexdbgPointer,
    tidptr: Pointer?,
    tid: Int
) : ThreadTask(tid, emulator.getReturnAddress()) {

    override fun setExitStatus(status: Int) {
        super.setExitStatus(status)

        if (tidptr != null) {
            // CLONE_CHILD_CLEARTID: zero the tid on exit so a joiner's futex wakes up.
            tidptr!!.setInt(0L, 0)
        }
    }

    private var errno: Pointer? = null

    override fun setErrno(emulator: Emulator<*>, errno: Int): Boolean {
        if (this.errno != null) {
            this.errno!!.setInt(0L, errno)
            return true
        }
        return super.setErrno(emulator, errno)
    }

    override fun toThreadString(): String {
        return String.format("MarshmallowThread tid=%d, fn=%s, arg=%s", id, fn, thread)
    }

    override fun runThread(emulator: AbstractEmulator<*>): Number? {
        val backend = emulator.getBackend()
        val stack = allocateStack(emulator)
        if (emulator.is32Bit()) {
            val tls = thread.share(0x48L)
            this.errno = tls.share(8L)
            backend.reg_write(ArmConst.UC_ARM_REG_R0, VortexdbgPointer.nativeValueOf(thread))
            backend.reg_write(ArmConst.UC_ARM_REG_SP, stack.peer)
            backend.reg_write(ArmConst.UC_ARM_REG_C13_C0_3, VortexdbgPointer.nativeValueOf(tls))
            backend.reg_write(ArmConst.UC_ARM_REG_LR, until)
        } else {
            val tls = thread.share(0xb0L)
            this.errno = tls.share(16L)
            backend.reg_write(Arm64Const.UC_ARM64_REG_X0, VortexdbgPointer.nativeValueOf(thread))
            backend.reg_write(Arm64Const.UC_ARM64_REG_SP, stack.peer)
            backend.reg_write(Arm64Const.UC_ARM64_REG_TPIDR_EL0, VortexdbgPointer.nativeValueOf(tls))
            backend.reg_write(Arm64Const.UC_ARM64_REG_LR, until)
        }
        return emulator.emulate(this.fn.peer, until)
    }

    private var tidptr: Pointer? = tidptr

    fun set_tid_address(tidptr: Pointer?) {
        this.tidptr = tidptr
    }

}
