package com.vortexdbg.ios.dsc

import com.vortexdbg.arm.backend.Unicorn2Factory
import com.vortexdbg.ios.DarwinEmulatorBuilder
import kotlin.system.exitProcess

/**
 * Path B — exercita a API consolidada [VortexDscLoader]: resolve e chama várias funções do cache
 * pelo mesmo loader (segunda chamada na mesma dylib reusa a região de código já mapeada).
 */
object LoaderApiDemo {

    private const val PLATFORM = "/usr/lib/system/libsystem_platform.dylib"

    @JvmStatic
    fun main(args: Array<String>) {
        val cfg = VortexIosConfig.load()
        val emulator = DarwinEmulatorBuilder.for64Bit().addBackendFactory(Unicorn2Factory(true)).build()
        val loader = VortexDscLoader.from(cfg, emulator)
        try {
            val strlen = loader.resolve(PLATFORM, "__platform_strlen")
                ?: run { System.err.println("não resolveu __platform_strlen"); exitProcess(2) }
            val strnlen = loader.resolve(PLATFORM, "__platform_strnlen")
                ?: run { System.err.println("não resolveu __platform_strnlen"); exitProcess(2) }

            println("=== Path B — API VortexDscLoader (${loader.imagePaths.size} dylibs no cache) ===")
            println("__platform_strlen  @ 0x%x".format(strlen.toLong()))
            println("__platform_strnlen @ 0x%x".format(strnlen.toLong()))

            data class Case(val desc: String, val got: Long, val want: Long)
            val cases = listOf(
                Case("strlen(\"VortexDBG\")", loader.call(strlen, loader.cString("VortexDBG")), 9),
                Case("strlen(\"\")", loader.call(strlen, loader.cString("")), 0),
                Case("strnlen(\"VortexDBG\", 4)", loader.call(strnlen, loader.cString("VortexDBG"), 4), 4),
                Case("strnlen(\"Vor\", 10)", loader.call(strnlen, loader.cString("Vor"), 10), 3),
            )
            var ok = true
            for (c in cases) {
                val pass = c.got == c.want
                if (!pass) ok = false
                println("  %-26s = %d (esperado %d) %s".format(c.desc, c.got, c.want, if (pass) "OK" else "FALHOU"))
            }
            println("páginas de DATA faltadas sob demanda = ${loader.lazy.pagesMapped}")
            println("RESULTADO: " + if (ok) "OK (API resolve+chama funções do cache; multi-call reusa região)" else "FALHOU")
            if (!ok) exitProcess(1)
        } finally {
            loader.close()
            emulator.close()
        }
    }
}
