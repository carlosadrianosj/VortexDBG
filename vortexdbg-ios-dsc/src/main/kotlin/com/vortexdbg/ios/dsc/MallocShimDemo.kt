package com.vortexdbg.ios.dsc

import com.vortexdbg.arm.backend.Unicorn2Factory
import com.vortexdbg.ios.DarwinEmulatorBuilder
import kotlin.system.exitProcess

/**
 * Path B [shim] — prova o MECANISMO de shim: chama malloc/calloc/realloc/free do cache (cujos
 * corpos reais precisariam do bootstrap do dyld4) e verifica que o nosso heap emulado atende.
 * Cada chamada entra no endereço real da função no cache, o CodeHook intercepta, computa em Java
 * e força o retorno (PC=LR) — o corpo real nunca roda. É a base pro runtime ObjC (malloc shimado).
 */
object MallocShimDemo {

    private const val LIBM = "/usr/lib/system/libsystem_malloc.dylib"

    @JvmStatic
    fun main(args: Array<String>) {
        val cfg = VortexIosConfig.load()
        val emulator = DarwinEmulatorBuilder.for64Bit().addBackendFactory(Unicorn2Factory(true)).build()
        val loader = VortexDscLoader.from(cfg, emulator)
        try {
            val shimmed = loader.installMallocShim()
            println("=== Path B — shim de malloc (heap emulado) ===")
            println("shims instalados: $shimmed")

            val malloc = loader.resolve(LIBM, "_malloc")!!
            val calloc = loader.resolve(LIBM, "_calloc")!!
            val realloc = loader.resolve(LIBM, "_realloc")!!
            val free = loader.resolve(LIBM, "_free")!!
            val backend = emulator.backend
            var ok = true

            // malloc(64): deve cair no heap emulado, ser gravável e legível.
            val p = loader.call(malloc, 64)
            backend.mem_write(p, "abcDEF".toByteArray(Charsets.US_ASCII))
            val r1 = String(backend.mem_read(p, 6), Charsets.US_ASCII)
            val inHeap = p >= loader.heap.base && p < loader.heap.base + loader.heap.size
            println("malloc(64) -> 0x%x (no heap=%s) grava/le=\"%s\"".format(p, inHeap, r1))
            ok = ok && inHeap && r1 == "abcDEF"

            // calloc(4,8)=32 bytes zerados.
            val z = loader.call(calloc, 4, 8)
            val zeros = backend.mem_read(z, 32).all { it.toInt() == 0 }
            println("calloc(4,8) -> 0x%x (zerado=%s)".format(z, zeros))
            ok = ok && zeros

            // realloc(p, 128): novo ptr, conteúdo preservado.
            val p2 = loader.call(realloc, p, 128)
            val r2 = String(backend.mem_read(p2, 6), Charsets.US_ASCII)
            println("realloc(p,128) -> 0x%x (preservou=\"%s\")".format(p2, r2))
            ok = ok && r2 == "abcDEF"

            // free(p2): no-op, mas a chamada tem que entrar/sair limpa.
            loader.call(free, p2)
            println("free(p2) ok; shims chamados=${loader.shimCalls}, páginas faltadas=${loader.lazy.pagesMapped}")
            ok = ok && loader.shimCalls >= 4

            println("RESULTADO: " + if (ok) "OK (malloc/calloc/realloc/free shimados; heap emulado funciona)" else "FALHOU")
            if (!ok) exitProcess(1)
        } catch (e: Exception) {
            val pc = emulator.backend.reg_read(unicorn.Arm64Const.UC_ARM64_REG_PC).toLong()
            System.err.println("ERRO: ${e.message} (PC=0x%x, shims=${loader.shimCalls})".format(pc))
            exitProcess(1)
        } finally {
            loader.close(); emulator.close()
        }
    }
}
