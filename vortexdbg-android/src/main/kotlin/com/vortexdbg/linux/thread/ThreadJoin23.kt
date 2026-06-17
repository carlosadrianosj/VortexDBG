package com.vortexdbg.linux.thread

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.Symbol
import com.vortexdbg.arm.HookStatus
import com.vortexdbg.hook.HookContext
import com.vortexdbg.hook.InlineHook
import com.vortexdbg.hook.ReplaceCallback
import com.vortexdbg.memory.Memory
import com.vortexdbg.unix.ThreadJoinVisitor
import com.sun.jna.Pointer

import java.util.concurrent.atomic.AtomicLong

object ThreadJoin23 {

    @JvmStatic
    fun patch(emulator: Emulator<*>, inlineHook: InlineHook, visitor: ThreadJoinVisitor?) {
        val memory = emulator.getMemory()
        val libc = memory.findModule("libc.so")
        val clone = libc!!.findSymbolByName("clone", false)
        val pthread_join = libc.findSymbolByName("pthread_join", false)
        if (clone == null || pthread_join == null) {
            throw IllegalStateException("clone=$clone, pthread_join=$pthread_join")
        }
        val value_ptr = AtomicLong()
        inlineHook.replace(pthread_join, object : ReplaceCallback() {
            override fun onCall(emulator: Emulator<*>, context: HookContext, originFunction: Long): HookStatus {
                val ptr = context.getPointerArg(1)
                if (ptr != null) {
                    if (emulator.is64Bit()) {
                        ptr.setLong(0L, value_ptr.get())
                    } else {
                        ptr.setInt(0L, value_ptr.get().toInt())
                    }
                }
                return HookStatus.LR(emulator, 0)
            }
        })
        inlineHook.replace(clone, if (emulator.is32Bit()) ClonePatcher32(visitor, value_ptr) else ClonePatcher64(visitor, value_ptr))
    }

}
