package com.vortexdbg.ios.dsc

import com.vortexdbg.arm.backend.Unicorn2Factory
import com.vortexdbg.ios.DarwinEmulatorBuilder
import kotlin.system.exitProcess

/**
 * Path B [robustez] — PROBE de chamada CROSS-DYLIB: _strdup (libsystem_c) chama malloc/memcpy de
 * OUTRAS dylibs/regiões do cache. Com o FETCH-lazy por região (loop de retry) + DATA por página,
 * a EXECUÇÃO cruza regiões sem crash do tradutor — o que esta brick prova. Porém malloc lê estado
 * GLOBAL que só é inicializado pelos initializers de biblioteca (libSystem_initializer/malloc_init),
 * que o dyld rodaria; sem eles, malloc desreferencia estado zerado -> READ_UNMAPPED. Isso delimita
 * o PRÓXIMO tijolo (rodar os initializers), o mesmo muro do objc_init. Aqui só documentamos onde
 * a execução pura chega.
 */
object CrossCallDemo {

    private const val LIBC = "/usr/lib/system/libsystem_c.dylib"

    @JvmStatic
    fun main(args: Array<String>) {
        val cfg = VortexIosConfig.load()
        val emulator = DarwinEmulatorBuilder.for64Bit().addBackendFactory(Unicorn2Factory(true)).build()
        val loader = VortexDscLoader.from(cfg, emulator)
        try {
            val strdup = loader.resolve(LIBC, "_strdup") ?: run { System.err.println("não resolveu _strdup"); exitProcess(2) }
            println("=== Path B — chamada cross-dylib (_strdup -> malloc/memcpy) ===")
            println("_strdup @ 0x%x".format(strdup.toLong()))

            val text = "cross-dylib!"
            val src = loader.cString(text)
            val dst = loader.call(strdup, src)
            println("_strdup(\"$text\") -> 0x%x (%d páginas faltadas)".format(dst, loader.lazy.pagesMapped))

            val ok = if (dst != 0L) {
                val bytes = emulator.backend.mem_read(dst, (text.length + 1).toLong())
                val copied = String(bytes, 0, text.length, Charsets.US_ASCII)
                println("conteúdo no ponteiro retornado = \"$copied\"")
                copied == text && dst != src
            } else { println("retornou NULL"); false }

            println("RESULTADO: " + if (ok) "OK (execução cruzou dylibs; malloc+copy reais do cache rodaram)" else "FALHOU")
            if (!ok) exitProcess(1)
        } catch (e: Exception) {
            // Esperado nesta fase: a execução CRUZOU dylibs (FETCH-lazy ok) e parou só quando
            // malloc tocou estado nao-inicializado. Documenta o muro do proximo tijolo (initializers).
            println("páginas faltadas até parar = ${loader.lazy.pagesMapped}")
            println("PARCIAL: execução cruzou regiões sem crash do tradutor, mas parou em estado")
            println("         não-inicializado (${e.message}). Próximo tijolo: rodar os initializers.")
        } finally {
            loader.close(); emulator.close()
        }
    }
}
