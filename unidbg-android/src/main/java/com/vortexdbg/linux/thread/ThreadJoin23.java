package com.vortexdbg.linux.thread;

import com.vortexdbg.Emulator;
import com.vortexdbg.Module;
import com.vortexdbg.Symbol;
import com.vortexdbg.arm.HookStatus;
import com.vortexdbg.hook.HookContext;
import com.vortexdbg.hook.InlineHook;
import com.vortexdbg.hook.ReplaceCallback;
import com.vortexdbg.memory.Memory;
import com.vortexdbg.unix.ThreadJoinVisitor;
import com.sun.jna.Pointer;

import java.util.concurrent.atomic.AtomicLong;

public class ThreadJoin23 {

    public static void patch(final Emulator<?> emulator, InlineHook inlineHook, final ThreadJoinVisitor visitor) {
        Memory memory = emulator.getMemory();
        Module libc = memory.findModule("libc.so");
        Symbol clone = libc.findSymbolByName("clone", false);
        Symbol pthread_join = libc.findSymbolByName("pthread_join", false);
        if (clone == null || pthread_join == null) {
            throw new IllegalStateException("clone=" + clone + ", pthread_join=" + pthread_join);
        }
        final AtomicLong value_ptr = new AtomicLong();
        inlineHook.replace(pthread_join, new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator<?> emulator, HookContext context, long originFunction) {
                Pointer ptr = context.getPointerArg(1);
                if (ptr != null) {
                    if (emulator.is64Bit()) {
                        ptr.setLong(0, value_ptr.get());
                    } else {
                        ptr.setInt(0, (int) value_ptr.get());
                    }
                }
                return HookStatus.LR(emulator, 0);
            }
        });
        inlineHook.replace(clone, emulator.is32Bit() ? new ClonePatcher32(visitor, value_ptr) : new ClonePatcher64(visitor, value_ptr));
    }

}
