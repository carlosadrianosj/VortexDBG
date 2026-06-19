package com.vortexdbg.app

import com.vortexdbg.AndroidEmulator
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmObject

/**
 * The "other half" of the bridge (Vortex-DBG / A1): routing Java(host) -> native(emulated).
 *
 * native methods of the app's classes, rewritten by [VortexNativeInstrumentor], stop being
 * native and instead call these dispatchers, which run the corresponding native function
 * INSIDE UniDBG (the emulated .so) and return the result to the host.
 *
 * The active session is thread-local — install it with [setSession] before invoking the
 * instrumented methods.
 */
object VortexNativeDispatch {

    private val CURRENT = ThreadLocal<VortexSession>()

    @JvmStatic
    fun setSession(session: VortexSession) {
        CURRENT.set(session)
    }

    @JvmStatic
    fun clearSession() {
        CURRENT.remove()
    }

    private fun session(): VortexSession {
        val s = CURRENT.get()
            ?: throw IllegalStateException(
                "nenhuma VortexSession ativa nesta thread " +
                    "(chame VortexNativeDispatch.setSession antes de invocar métodos nativos do app)"
            )
        return s
    }

    private fun dvmClass(className: String): DvmClass {
        return session().resolveNativeClass(className.replace('.', '/'))
    }

    private fun emulator(): AndroidEmulator {
        return session().emulator()
    }

    // ---- dispatchers by return type (JNI signature = method + descriptor) ----

    @JvmStatic
    fun dispatchVoid(className: String, method: String, descriptor: String, args: Array<Any?>) {
        dvmClass(className).callStaticJniMethod(emulator(), method + descriptor, *args)
    }

    @JvmStatic
    fun dispatchBoolean(className: String, method: String, descriptor: String, args: Array<Any?>): Boolean {
        return dvmClass(className).callStaticJniMethodBoolean(emulator(), method + descriptor, *args)
    }

    @JvmStatic
    fun dispatchInt(className: String, method: String, descriptor: String, args: Array<Any?>): Int {
        return dvmClass(className).callStaticJniMethodInt(emulator(), method + descriptor, *args)
    }

    @JvmStatic
    fun dispatchLong(className: String, method: String, descriptor: String, args: Array<Any?>): Long {
        return dvmClass(className).callStaticJniMethodLong(emulator(), method + descriptor, *args)
    }

    @JvmStatic
    fun dispatchObject(className: String, method: String, descriptor: String, args: Array<Any?>): Any? {
        val r: DvmObject<*>? =
            dvmClass(className).callStaticJniMethodObject(emulator(), method + descriptor, *args)
        return r?.getValue()
    }
}
