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

import java.util.concurrent.atomic.AtomicInteger

object ThreadJoin19 {

    @JvmStatic
    fun patch(emulator: Emulator<*>, inlineHook: InlineHook, visitor: ThreadJoinVisitor?) {
        if (emulator.is64Bit()) {
            throw IllegalStateException()
        }
        val memory = emulator.getMemory()
        val libc = memory.findModule("libc.so")
        val _pthread_clone = libc!!.findSymbolByName("__pthread_clone", false)
        val pthread_join = libc.findSymbolByName("pthread_join", false)
        if (_pthread_clone == null || pthread_join == null) {
            throw IllegalStateException("_pthread_clone=$_pthread_clone, pthread_join=$pthread_join")
        }
        val value_ptr = AtomicInteger()
        inlineHook.replace(pthread_join, object : ReplaceCallback() {
            override fun onCall(emulator: Emulator<*>, context: HookContext, originFunction: Long): HookStatus {
                val ptr = context.getPointerArg(1)
                if (ptr != null) {
                    ptr.setInt(0L, value_ptr.get())
                }
                return HookStatus.LR(emulator, 0)
            }
        })
        inlineHook.replace(_pthread_clone, ThreadClonePatcher32(visitor, value_ptr))
    }

}
