package com.vortexdbg.ios.dsc

import com.vortexdbg.arm.backend.Unicorn2Factory
import com.vortexdbg.ios.DarwinEmulatorBuilder
import java.io.File
import java.io.RandomAccessFile
import kotlin.system.exitProcess

/**
 * Path B [2] (prova) — mapeia UMA região do dyld_shared_cache na memória do emulador (no
 * vmaddr do cache 0x180000000) e lê de volta: o header do cache deve estar legível lá
 * (magic 'dyld_v1'). Prova o mecanismo de mapeamento sem carregar os 3 GB.
 */
object MapOneDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        val cfg = VortexIosConfig.load()
        val mainCache = findMainCache(cfg.rootfs.parentFile, cfg.arch)
            ?: run { System.err.println("cache não encontrado (rode fetch-dsc)"); exitProcess(2) }

        val cache = DyldSharedCache(mainCache)
        val first = cache.mappings.first()
        println("=== Path B [2] — mapear região do cache na memória emulada ===")
        println("região: $first")

        val emulator = DarwinEmulatorBuilder.for64Bit()
            .addBackendFactory(Unicorn2Factory(true)) // Unicorn2 (sem hypervisor/entitlement)
            .build()
        try {
            val mapper = DscMapper(emulator)
            RandomAccessFile(mainCache, "r").use { raf ->
                mapper.mapOne(raf, first)
            }
            // ler de volta no vmaddr do cache
            val addr = first.address.toLong()
            val magicBytes = emulator.backend.mem_read(addr, 7)
            val magic = String(magicBytes, Charsets.US_ASCII)
            println("mem_read(0x%x, 7) = '%s'".format(addr, magic))

            val ok = magic == "dyld_v1"
            println(
                "RESULTADO Path B [2]: " + if (ok) {
                    "OK (região do cache mapeada em 0x%x e legível na memória emulada)".format(addr)
                } else {
                    "FALHOU"
                },
            )
            if (!ok) exitProcess(1)
        } finally {
            emulator.close()
        }
    }

    private fun findMainCache(dir: File, arch: String): File? {
        dir.listFiles { f -> f.isDirectory }?.forEach { d ->
            val c = File(d, "dyld_shared_cache_$arch")
            if (c.isFile) return c
        }
        val c = File(dir, "dyld_shared_cache_$arch")
        return if (c.isFile) c else null
    }
}
