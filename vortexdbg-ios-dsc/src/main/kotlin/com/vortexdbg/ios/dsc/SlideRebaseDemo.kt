package com.vortexdbg.ios.dsc

import com.vortexdbg.arm.backend.Unicorn2Factory
import com.vortexdbg.ios.DarwinEmulatorBuilder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

/**
 * Path B [3] — aplica slide info V5 ao __DATA da libobjc e prova que os ponteiros encadeados
 * viram VAs reais. Mapeia a região DATA (.dylddata) que cobre __DATA da libobjc, lê um ponteiro
 * ANTES (packed), roda o rebaser, e lê DEPOIS (VA real no shared region).
 *
 * Esperado (validado por dump cru): em +0x60 do __DATA o ponteiro packed 0x00200000001596cc
 * (runtimeOffset=0x1596cc, next=2) deve virar 0x180000000+0x1596cc = 0x1801596cc.
 */
object SlideRebaseDemo {

    private const val SHARED_BASE = 0x180000000L
    private const val SHARED_END = 0x280000000L

    @JvmStatic
    fun main(args: Array<String>) {
        val cfg = VortexIosConfig.load()
        val mainCache = findMainCache(cfg.rootfs.parentFile, cfg.arch)
            ?: run { System.err.println("cache não encontrado (rode fetch-dsc)"); exitProcess(2) }

        // __DATA da libobjc: descoberto via os segmentos (SegmentsDemo). Início = 0x1eb2b8000.
        val dataAddr = 0x1eb2b8000uL
        val probe = dataAddr + 0x60uL

        println("=== Path B — aplicar slide info V5 (libobjc __DATA) ===")
        val emulator = DarwinEmulatorBuilder.for64Bit().addBackendFactory(Unicorn2Factory(true)).build()
        try {
            val files = DyldSharedCache.cacheFiles(mainCache)
            val (dataCache, mp) = DscMapper(emulator).mapSlideRegionContaining(files, dataAddr)
                ?: run { System.err.println("região DATA com slide info não encontrada para 0x%x".format(dataAddr.toLong())); exitProcess(1) }
            val si = dataCache.slideInfo5(mp)
                ?: run { System.err.println("slide info não é V5"); exitProcess(1) }
            println("DATA mapeado: addr=0x%x size=0x%x  slideInfo V5: pageSize=0x%x value_add=0x%x pages=%d"
                .format(mp.address.toLong(), mp.size.toLong(), si.pageSize, si.valueAdd.toLong(), si.pageStarts.size))

            val before = readU64(emulator, probe.toLong())
            val n = SlideRebaser.rebase(emulator, mp, si)
            val after = readU64(emulator, probe.toLong())

            println("ponteiros rebaseados: %d".format(n))
            println("probe @0x%x: antes=0x%016x  depois=0x%016x".format(probe.toLong(), before, after))

            // Validação: depois deve ser um VA no shared region, e bater com value_add+runtimeOffset.
            val expected = si.valueAdd.toLong() + (before and 0x3FFFFFFFFL)
            val ok = n > 0 && after in SHARED_BASE until SHARED_END && after == expected && before != after
            println("esperado (value_add+runtimeOffset)=0x%016x".format(expected))
            println("RESULTADO: " + if (ok) "OK (slide V5 aplicada; chained ptr -> VA real 0x%x)".format(after) else "FALHOU")
            if (!ok) exitProcess(1)
        } finally {
            emulator.close()
        }
    }

    private fun readU64(emulator: com.vortexdbg.Emulator<*>, addr: Long): Long =
        ByteBuffer.wrap(emulator.backend.mem_read(addr, 8)).order(ByteOrder.LITTLE_ENDIAN).getLong(0)

    private fun findMainCache(dir: File, arch: String): File? {
        dir.listFiles { f -> f.isDirectory }?.forEach { d ->
            val c = File(d, "dyld_shared_cache_$arch")
            if (c.isFile) return c
        }
        val c = File(dir, "dyld_shared_cache_$arch")
        return if (c.isFile) c else null
    }
}
