package com.vortexdbg.ios.dsc

import com.vortexdbg.arm.backend.Unicorn2Factory
import com.vortexdbg.ios.DarwinEmulatorBuilder
import java.io.File
import kotlin.system.exitProcess

/**
 * Path B [símbolos] — resolve símbolos exportados pela libobjc lendo a export trie direto do
 * cache. Mapeia o __TEXT da libobjc, parseia os load commands e resolve _objc_msgSend & cia,
 * validando que caem dentro do __TEXT da dylib.
 */
object SymbolResolveDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        val cfg = VortexIosConfig.load()
        val mainCache = findMainCache(cfg.rootfs.parentFile, cfg.arch)
            ?: run { System.err.println("cache não encontrado (rode fetch-dsc)"); exitProcess(2) }

        val cache = DyldSharedCache(mainCache)
        val image = cache.images().first { it.path == "/usr/lib/libobjc.A.dylib" }
        val wanted = if (args.isNotEmpty()) args.toList() else listOf("_objc_msgSend", "_objc_getClass", "_class_getName", "_sel_registerName")

        println("=== Path B — resolver símbolos do cache (libobjc @ 0x%x) ===".format(image.loadAddress.toLong()))
        val emulator = DarwinEmulatorBuilder.for64Bit().addBackendFactory(Unicorn2Factory(true)).build()
        try {
            val files = DyldSharedCache.cacheFiles(mainCache)
            DscMapper(emulator).mapRegionContaining(files, image.loadAddress)
                ?: run { System.err.println("não mapeou o __TEXT da libobjc"); exitProcess(1) }

            val text = CachedMacho.parse(emulator, image.loadAddress).segment("__TEXT")!!
            val textStart = text.vmaddr
            val textEnd = text.vmaddr + text.vmsize

            val symbols = CacheSymbols(emulator, files)
            var ok = true
            for (sym in wanted) {
                val va = symbols.exportedSymbol(image.loadAddress, sym)
                val inText = va != null && va >= textStart && va < textEnd
                println("  %-22s -> %s %s".format(sym,
                    va?.let { "0x%x".format(it.toLong()) } ?: "NÃO ENCONTRADO",
                    if (va != null) (if (inText) "(em __TEXT)" else "(FORA do __TEXT!)") else ""))
                if (!inText) ok = false
            }
            println("RESULTADO: " + if (ok) "OK (símbolos resolvidos via export trie do cache, todos em __TEXT)" else "FALHOU")
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
