package com.vortexdbg.kotlin

import com.vortexdbg.app.VortexSession
import java.io.File

/**
 * PILOTO da integração Kotlin (Vortex-DBG / A1).
 *
 * O CORE continua em Java (mergeability com o upstream). Esta camada adiciona só
 * ERGONOMIA sobre a API Java existente: um DSL type-safe para abrir uma [VortexSession]
 * e extensões com null-safety. Kotlin interopera 100% com o Java — nada do core muda.
 *
 * Exemplo:
 * ```
 * vortexSession {
 *     classes("app-classes.jar")
 *     androidAll("android-all.jar")
 *     nativeLib("libfoo.so")
 *     sdk = 23
 * }.use { s ->
 *     val r: String? = s.invokeStaticAs("com.app.Crypto", "decrypt",
 *             arrayOf(String::class.java), "deadbeef")
 * }
 * ```
 */
class VortexSessionSpec {
    private val builder = VortexSession.builder()

    var sdk: Int = 23
    var arch64: Boolean = true
    var verbose: Boolean = false
    var exceptionPropagation: Boolean = true
    var callJniOnLoad: Boolean = false
    var processName: String = "vortex"

    fun classes(vararg jarsOrDirs: File) = apply { builder.classes(*jarsOrDirs) }
    fun classes(vararg paths: String) = apply { builder.classes(*paths.map(::File).toTypedArray()) }

    fun androidAll(jar: File) = apply { builder.androidAll(jar) }
    fun androidAll(path: String) = apply { builder.androidAll(File(path)) }

    fun nativeLib(vararg so: File) = apply { builder.nativeLib(*so) }
    fun nativeLib(vararg paths: String) = apply { builder.nativeLib(*paths.map(::File).toTypedArray()) }

    internal fun build(): VortexSession = builder
        .sdk(sdk)
        .arch64(arch64)
        .verbose(verbose)
        .exceptionPropagation(exceptionPropagation)
        .callJniOnLoad(callJniOnLoad)
        .processName(processName)
        .open()
}

/** Abre uma [VortexSession] via DSL type-safe. */
fun vortexSession(block: VortexSessionSpec.() -> Unit): VortexSession =
    VortexSessionSpec().apply(block).build()

/**
 * Invoca um método estático de uma classe do app e devolve o valor já tipado (ou null) —
 * a versão Kotlin com null-safety e generics reificados do [VortexSession.invokeStatic].
 */
inline fun <reified T> VortexSession.invokeStaticAs(
    className: String,
    method: String,
    paramTypes: Array<Class<*>>,
    vararg args: Any?,
): T? = invokeStatic(className, method, paramTypes, *args) as? T
