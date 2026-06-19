package com.vortexdbg.app

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.debugger.BreakPointCallback
import com.vortexdbg.debugger.Debugger
import com.vortexdbg.linux.android.dvm.DvmMethod
import com.vortexdbg.linux.android.dvm.Jni
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Dual-layer debugger for Vortex-DBG (A1).
 *
 * Unifies BOTH layers under a single view:
 *   - NATIVE: reuses UniDBG's ARM debugger ([Emulator.attach]) — breakpoints by
 *     symbol/address, registers and native backtrace (via Unwinder).
 *   - JAVA / JNI boundary: wraps the [Jni] to observe every native->java crossing
 *     (any call*Method), so execution can be "followed" across the bridge and a unified
 *     stack assembled (native frames + Java method).
 *
 * Non-interactive (callback-based) — foundation for an interactive frontend (REPL/IDE) later.
 */
open class VortexDebugger(private val emulator: Emulator<*>) {

    private val nativeDebugger: Debugger = emulator.attach()

    open fun nativeDebugger(): Debugger {
        return nativeDebugger
    }

    // ---------- native breakpoints ----------

    interface NativeBreakHandler {
        fun onBreak(ctx: NativeBreakContext)
    }

    open fun breakNative(module: Module, symbol: String, handler: NativeBreakHandler) {
        nativeDebugger.addBreakPoint(module, symbol, object : BreakPointCallback {
            override fun onHit(emu: Emulator<*>, address: Long): Boolean {
                handler.onBreak(NativeBreakContext(address))
                return true // handled -> resume (do not enter the interactive debugger)
            }
        })
    }

    open fun breakNative(address: Long, handler: NativeBreakHandler) {
        nativeDebugger.addBreakPoint(address, object : BreakPointCallback {
            override fun onHit(emu: Emulator<*>, addr: Long): Boolean {
                handler.onBreak(NativeBreakContext(addr))
                return true
            }
        })
    }

    // ---------- JNI seam observation (native -> java) ----------

    interface JavaCrossHandler {
        fun onCross(signature: String, rawArgs: Array<Any?>)
    }

    /**
     * Wraps a base [Jni], firing [handler] on every native->java call (any call*Method).
     * Install with vm.setJni(debugger.instrument(jni, h)).
     */
    open fun instrument(base: Jni, handler: JavaCrossHandler): Jni {
        return Proxy.newProxyInstance(
            Jni::class.java.classLoader,
            arrayOf<Class<*>>(Jni::class.java),
            object : InvocationHandler {
                @Throws(Throwable::class)
                override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
                    if (method.name.startsWith("call") && args != null && args.size >= 3) {
                        handler.onCross(signatureOf(args[2]), args)
                    }
                    try {
                        return method.invoke(base, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            }) as Jni
    }

    // ---------- native stack / backtrace ----------

    open fun nativeStack(maxDepth: Int): List<String> {
        val out = ArrayList<String>()
        try {
            for (f in emulator.getUnwinder().getFrames(maxDepth)) {
                out.add(if (f.ip == null) "?" else f.ip.toString())
            }
        } catch (t: Throwable) {
            out.add("<unwind indisponível: " + t.javaClass.simpleName + ">")
        }
        return out
    }

    /** Contexto de um breakpoint nativo: PC, registradores, args e backtrace. */
    inner class NativeBreakContext internal constructor(private val address: Long) {

        fun pc(): Long {
            return address
        }

        fun regs(): RegisterContext {
            return emulator.getContext<RegisterContext>()
        }

        /** Argumento i da convenção de chamada (X0/R0 = i 0). */
        fun arg(i: Int): Long {
            return emulator.getContext<RegisterContext>().getLongArg(i)
        }

        fun nativeStack(maxDepth: Int): List<String> {
            return this@VortexDebugger.nativeStack(maxDepth)
        }
    }

    companion object {
        private fun signatureOf(arg: Any?): String {
            if (arg is String) {
                return arg
            }
            if (arg is DvmMethod) {
                return arg.toString()
            }
            return arg.toString()
        }
    }
}
