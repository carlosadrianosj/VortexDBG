package com.vortexdbg.ios.dsc

import java.io.File
import kotlin.system.exitProcess

/**
 * Path B [1] — runnable: parseia o dyld_shared_cache real (main + sub-caches) e lista as
 * mappings que serão mapeadas na memória do emulador. Valida vs `ipsw dyld info`.
 */
object DscReaderMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val cfg = VortexIosConfig.load()
        val mainCache = findMainCache(cfg.rootfs.parentFile, cfg.arch)
            ?: run {
                System.err.println("cache não encontrado sob ${cfg.rootfs.parentFile} (rode fetch-dsc)")
                exitProcess(2)
            }

        println("=== Path B [1] — DyldSharedCache reader (Kotlin) ${cfg.version} ${cfg.arch} ===")
        val files = DyldSharedCache.cacheFiles(mainCache)
        println("arquivos do cache (main + sub-caches): ${files.size}")

        var totalMapped = 0UL
        var minAddr = ULong.MAX_VALUE
        var maxAddr = 0UL
        var mainMagic = ""
        files.forEachIndexed { i, f ->
            val c = DyldSharedCache(f)
            if (i == 0) mainMagic = c.magic
            for (mp in c.mappings) {
                totalMapped += mp.size
                if (mp.address < minAddr) minAddr = mp.address
                if (mp.address + mp.size > maxAddr) maxAddr = mp.address + mp.size
            }
            if (i < 3) {
                println("  ${f.name}  magic='${c.magic}'")
                c.mappings.forEach { println("      $it") }
            }
        }
        println(
            "TOTAL: ${files.size} arquivos, %.1f GB mapeáveis, região 0x%x -> 0x%x".format(
                totalMapped.toLong() / 1024.0 / 1024.0 / 1024.0, minAddr.toLong(), maxAddr.toLong(),
            ),
        )

        val ok = mainMagic.startsWith("dyld_v1") && minAddr == 0x180000000UL && files.size > 1
        println("RESULTADO Path B (Kotlin reader): " + if (ok) "OK (cache parseado: '$mainMagic', base 0x180000000, ${files.size} arquivos)" else "FALHOU")
        if (!ok) exitProcess(1)
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
