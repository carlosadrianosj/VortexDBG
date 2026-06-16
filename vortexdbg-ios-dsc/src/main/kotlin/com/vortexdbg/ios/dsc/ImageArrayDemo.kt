package com.vortexdbg.ios.dsc

import java.io.File
import kotlin.system.exitProcess

/**
 * Path B [4] — lista as imagens (dylibs) do dyld_shared_cache (endereço + path) e acha o
 * libobjc. Valida vs `ipsw dyld info` (Num Images = 3861). É o que permite mapear SÓ o que
 * um alvo precisa, em vez dos 3 GB.
 */
object ImageArrayDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        val cfg = VortexIosConfig.load()
        val mainCache = findMainCache(cfg.rootfs.parentFile, cfg.arch)
            ?: run { System.err.println("cache não encontrado (rode fetch-dsc)"); exitProcess(2) }

        val cache = DyldSharedCache(mainCache)
        val images = cache.images()
        println("=== Path B [4] — image array (${cfg.version} ${cfg.arch}) ===")
        println("imagens (dylibs) no cache: ${images.size}")

        println("amostra:")
        images.take(5).forEach { println("  0x%x  %s".format(it.loadAddress.toLong(), it.path)) }

        val libobjc = images.firstOrNull { it.path == "/usr/lib/libobjc.A.dylib" }
        val foundation = images.firstOrNull { it.path.endsWith("/Foundation") }
        println("libobjc:    " + (libobjc?.let { "0x%x".format(it.loadAddress.toLong()) } ?: "NÃO ACHOU"))
        println("Foundation: " + (foundation?.let { "0x%x  %s".format(it.loadAddress.toLong(), it.path) } ?: "NÃO ACHOU"))

        val ok = images.size == 3861 && libobjc != null
        println("RESULTADO Path B [4]: " + if (ok) "OK (3861 imagens parseadas, libobjc localizado)" else "FALHOU (size=${images.size}, libobjc=${libobjc != null})")
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
