package com.vortexdbg.ios.dsc

import com.vortexdbg.arm.backend.Unicorn2Factory
import com.vortexdbg.ios.DarwinEmulatorBuilder
import kotlin.system.exitProcess

/**
 * Path B [reexports] — resolve símbolos que são RE-EXPORTS (ex.: _strlen em libsystem_c aponta,
 * via ordinal, para a dylib que realmente o implementa) seguindo a cadeia, e os executa. Prova
 * que a resolução não para num flags!=0 e chega no código real.
 */
object ReexportDemo {

    private const val LIBC = "/usr/lib/system/libsystem_c.dylib"

    @JvmStatic
    fun main(args: Array<String>) {
        val cfg = VortexIosConfig.load()
        val emulator = DarwinEmulatorBuilder.for64Bit().addBackendFactory(Unicorn2Factory(true)).build()
        val loader = VortexDscLoader.from(cfg, emulator)
        try {
            println("=== Path B — reexports (resolver via libsystem_c) ===")
            var ok = true
            for ((sym, arg, want) in listOf(
                Triple("_strlen", "VortexDBG", 9L),
                Triple("_strlen", "abc", 3L),
            )) {
                val va = loader.resolve(LIBC, sym)
                if (va == null) { println("  $sym -> NÃO RESOLVIDO"); ok = false; continue }
                val got = loader.call(va, loader.cString(arg))
                val pass = got == want
                if (!pass) ok = false
                println("  %s(\"%s\") -> impl @0x%x = %d (esperado %d) %s".format(sym, arg, va.toLong(), got, want, if (pass) "OK" else "FALHOU"))
            }
            // memcmp (também reexport): retorna 0 quando iguais
            val memcmp = loader.resolve(LIBC, "_memcmp")
            if (memcmp != null) {
                val a = loader.cString("hello"); val b = loader.cString("hello")
                val r = loader.call(memcmp, a, b, 5)
                val pass = r == 0L
                if (!pass) ok = false
                println("  _memcmp(\"hello\",\"hello\",5) -> impl @0x%x = %d (esperado 0) %s".format(memcmp.toLong(), r, if (pass) "OK" else "FALHOU"))
            } else { println("  _memcmp -> NÃO RESOLVIDO"); ok = false }

            println("RESULTADO: " + if (ok) "OK (reexports seguidos até o código real e executados)" else "FALHOU")
            if (!ok) exitProcess(1)
        } finally {
            loader.close()
            emulator.close()
        }
    }
}
