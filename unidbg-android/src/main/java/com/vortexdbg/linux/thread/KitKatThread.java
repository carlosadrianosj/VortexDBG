package com.vortexdbg.linux.thread;

import com.vortexdbg.AbstractEmulator;
import com.vortexdbg.Emulator;
import com.vortexdbg.Module;
import com.vortexdbg.Symbol;
import com.vortexdbg.arm.backend.Backend;
import com.vortexdbg.pointer.UnidbgPointer;
import com.vortexdbg.thread.ThreadTask;
import com.sun.jna.Pointer;
import unicorn.ArmConst;

public class KitKatThread extends ThreadTask {

    private final UnidbgPointer child_stack;
    private final UnidbgPointer fn;
    private final UnidbgPointer arg;

    public KitKatThread(int tid, long until, UnidbgPointer child_stack, UnidbgPointer fn, UnidbgPointer arg) {
        super(tid, until);
        this.child_stack = child_stack;
        this.fn = fn;
        this.arg = arg;
    }

    private Pointer errno;

    @Override
    public boolean setErrno(Emulator<?> emulator, int errno) {
        if (this.errno != null) {
            this.errno.setInt(0, errno);
            return true;
        }
        return super.setErrno(emulator, errno);
    }

    @Override
    public String toThreadString() {
        return "KitKatThread fn=" + fn + ", arg=" + arg + ", child_stack=" + child_stack;
    }

    @Override
    protected Number runThread(AbstractEmulator<?> emulator) {
        Backend backend = emulator.getBackend();
        UnidbgPointer stack = allocateStack(emulator);
        backend.reg_write(ArmConst.UC_ARM_REG_SP, stack.peer);
        this.errno = child_stack.share(8);

        backend.reg_write(ArmConst.UC_ARM_REG_R0, this.fn.peer);
        backend.reg_write(ArmConst.UC_ARM_REG_R1, this.arg == null ? 0 : this.arg.peer);
        backend.reg_write(ArmConst.UC_ARM_REG_R2, this.child_stack.peer);
        backend.reg_write(ArmConst.UC_ARM_REG_LR, until);

        Module libc = emulator.getMemory().findModule("libc.so");
        Symbol __thread_entry = libc.findSymbolByName("__thread_entry", false);
        if (__thread_entry == null) {
            throw new IllegalStateException();
        }
        return emulator.emulate(__thread_entry.getAddress(), until);
    }

}
