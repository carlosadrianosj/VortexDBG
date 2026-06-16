package com.vortexdbg.ios.dsc

import com.vortexdbg.arm.backend.Unicorn2Factory
import com.vortexdbg.ios.DarwinEmulatorBuilder
import java.io.File
import kotlin.system.exitProcess

/**
 * Path B (tijolo) — parsear os SEGMENTOS de uma dylib do cache, lendo o Mach-O da memória
 * emulada. Acha+mapeia a dylib, então lista os LC_SEGMENT_64. Valida __TEXT no loadAddress.
 */
object SegmentsDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        val cfg = VortexIosConfig.load()
        val mainCache = findMainCache(cfg.rootfs.parentFile, cfg.arch)
            ?: run { System.err.println("cache não encontrado (rode fetch-dsc)"); exitProcess(2) }

        val cache = DyldSharedCache(mainCache)
        val target = args.getOrElse(0) { "/usr/lib/libobjc.A.dylib" }
        val image = cache.images().firstOrNull { it.path == target }
            ?: run { System.err.println("dylib não achada: $target"); exitProcess(2) }

        println("=== Path B — segmentos da dylib ($target @ 0x%x) ===".format(image.loadAddress.toLong()))
        val emulator = DarwinEmulatorBuilder.for64Bit().addBackendFactory(Unicorn2Factory(true)).build()
        try {
            val mapper = DscMapper(emulator)
            mapper.mapRegionContaining(DyldSharedCache.cacheFiles(mainCache), image.loadAddress)
                ?: run { System.err.println("não mapeou a região da dylib"); exitProcess(1) }

            val segs = CachedMacho.segments(emulator, image.loadAddress)
            println("segmentos (${segs.size}):")
            segs.forEach { println("  $it") }

            val text = segs.firstOrNull { it.name == "__TEXT" }
            val ok = segs.isNotEmpty() && text != null && text.vmaddr == image.loadAddress
            println("RESULTADO: " + if (ok) "OK (Mach-O parseado da memória; __TEXT no loadAddress, ${segs.size} segmentos)" else "FALHOU")
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
