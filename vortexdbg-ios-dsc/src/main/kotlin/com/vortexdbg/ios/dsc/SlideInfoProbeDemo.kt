package com.vortexdbg.ios.dsc

import java.io.File
import kotlin.system.exitProcess

/**
 * Path B [3] (probe) — enumera os mappings COM slide info nos arquivos do cache e lê o header
 * da slide info V5 (version/page_size/value_add/contagem de page_starts). Valida que o cache
 * moderno usa V5 e que conseguimos achar+parsear os metadados de rebase. Não reescreve nada.
 */
object SlideInfoProbeDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        val cfg = VortexIosConfig.load()
        val mainCache = findMainCache(cfg.rootfs.parentFile, cfg.arch)
            ?: run { System.err.println("cache não encontrado (rode fetch-dsc)"); exitProcess(2) }

        println("=== Path B — probe slide info V5 ===")
        val files = DyldSharedCache.cacheFiles(mainCache)
        var withSlide = 0
        var v5 = 0
        for (f in files) {
            val cache = DyldSharedCache(f)
            for (mp in cache.mappingsWithSlide) {
                if (mp.slideInfoFileSize == 0uL) continue
                withSlide++
                val si = cache.slideInfo5(mp)
                if (si != null) {
                    v5++
                    val rebasable = si.pageStarts.count { it != 0xFFFF }
                    println("%s @0x%x size=0x%x slideInfo=0x%x : V5 pageSize=0x%x value_add=0x%x pages=%d (rebasáveis=%d)"
                        .format(f.name, mp.address.toLong(), mp.size.toLong(),
                            mp.slideInfoFileOffset.toLong(), si.pageSize, si.valueAdd.toLong(),
                            si.pageStarts.size, rebasable))
                } else {
                    println("%s @0x%x : slide info presente mas NÃO é V5".format(f.name, mp.address.toLong()))
                }
            }
        }
        println("RESULTADO: mappings com slide=%d, V5=%d → %s".format(withSlide, v5,
            if (v5 > 0 && v5 == withSlide) "OK (todas V5)" else if (v5 > 0) "PARCIAL" else "FALHOU"))
        if (v5 == 0) exitProcess(1)
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
