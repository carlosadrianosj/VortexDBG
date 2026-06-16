package com.vortexdbg.ios.dsc

import com.vortexdbg.arm.backend.Unicorn2Factory
import com.vortexdbg.ios.DarwinEmulatorBuilder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

/**
 * Path B (tijolo) — localizar + mapear UMA dylib do cache e ler o Mach-O dela da memória
 * emulada. Combina [4] (image array) + [2] (mapear): acha libobjc via o image array, mapeia
 * o sub-cache que cobre o endereço dela, e lê o header Mach-O em mem (magic + filetype).
 * Mapeia só ~85MB (um sub-cache), não os 3 GB.
 */
object LocateMapDemo {

    private const val MH_MAGIC_64 = 0xFEEDFACF.toInt()
    private const val MH_DYLIB = 6

    @JvmStatic
    fun main(args: Array<String>) {
        val cfg = VortexIosConfig.load()
        val mainCache = findMainCache(cfg.rootfs.parentFile, cfg.arch)
            ?: run { System.err.println("cache não encontrado (rode fetch-dsc)"); exitProcess(2) }

        val cache = DyldSharedCache(mainCache)
        val target = args.getOrElse(0) { "/usr/lib/libobjc.A.dylib" }
        val image = cache.images().firstOrNull { it.path == target }
            ?: run { System.err.println("dylib não achada no cache: $target"); exitProcess(2) }

        println("=== Path B — localizar+mapear dylib ($target) ===")
        println("loadAddress = 0x%x".format(image.loadAddress.toLong()))

        val emulator = DarwinEmulatorBuilder.for64Bit()
            .addBackendFactory(Unicorn2Factory(true))
            .build()
        try {
            val files = DyldSharedCache.cacheFiles(mainCache)
            val mapper = DscMapper(emulator)
            val mapped = mapper.mapRegionContaining(files, image.loadAddress)
                ?: run { System.err.println("nenhum mapping contém 0x%x".format(image.loadAddress.toLong())); exitProcess(1) }
            println("mapeado o mapping que contém a dylib: $mapped")

            val addr = image.loadAddress.toLong()
            val hdr = emulator.backend.mem_read(addr, 16)
            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            val magic = bb.getInt(0)
            val filetype = bb.getInt(12)
            println("Mach-O @ 0x%x: magic=0x%08x filetype=%d".format(addr, magic, filetype))

            val ok = magic == MH_MAGIC_64 && filetype == MH_DYLIB
            println("RESULTADO: " + if (ok) "OK (dylib localizada e mapeada; Mach-O legível na memória emulada)" else "FALHOU")
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
