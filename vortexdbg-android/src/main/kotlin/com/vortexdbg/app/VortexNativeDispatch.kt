package com.vortexdbg.app

import com.vortexdbg.AndroidEmulator
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmObject

/**
 * E (Vortex-DBG / A1) — a "outra metade" da fusão: roteamento Java(host) → native(emulado).
 *
 * Métodos {@code native} das classes do app, reescritos por {@link VortexNativeInstrumentor},
 * deixam de ser nativos e passam a chamar estes dispatchers, que executam a função nativa
 * correspondente DENTRO do UniDBG (a `.so` emulada) e devolvem o resultado ao host.
 *
 * A sessão ativa é thread-local — instale com {@link #setSession(VortexSession)} antes de
 * invocar os métodos instrumentados.
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

    // ---- dispatchers por tipo de retorno (a assinatura JNI = method + descriptor) ----

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
