package com.vortexdbg.ios.dsc

import com.vortexdbg.Emulator
import java.io.File
import java.io.RandomAccessFile

/**
 * Path B [2] — mapeia as regiões do dyld_shared_cache na memória do emulador, NOS VMADDRS
 * do cache (como o dyld real faz). Lê os bytes do arquivo (no fileOffset) e escreve no
 * endereço do cache. Os bits de proteção do dyld (READ=1/WRITE=2/EXEC=4) batem com os do
 * Unicorn (UC_PROT_*), então são passados direto.
 *
 * NOTA: o cache inteiro são ~3 GB (61 arquivos). Mapear TUDO eagerly é pesado em RAM; o
 * mapeamento sob-demanda (lazy, por página) é um passo posterior. [mapOne] mapeia uma região
 * só (prova do mecanismo).
 */
class DscMapper(private val emulator: Emulator<*>) {

    private val pageAlign: Long = emulator.pageAlign.toLong()

    /** Mapeia todas as mappings de UM arquivo de cache. Retorna bytes escritos. */
    fun map(cache: DyldSharedCache): Long {
        var total = 0L
        RandomAccessFile(cache.file, "r").use { raf ->
            for (mp in cache.mappings) {
                total += mapOne(raf, mp)
            }
        }
        return total
    }

    /** Mapeia uma única mapping (região contígua) no vmaddr do cache. */
    fun mapOne(raf: RandomAccessFile, mp: DyldSharedCache.Mapping): Long {
        val backend = emulator.backend
        val addr = mp.address.toLong()
        val rawSize = mp.size.toLong()
        val mappedSize = align(rawSize)
        backend.mem_map(addr, mappedSize, mp.initProt.toInt())
        val data = ByteArray(rawSize.toInt())
        raf.seek(mp.fileOffset.toLong())
        raf.readFully(data)
        backend.mem_write(addr, data)
        return rawSize
    }

    /** Mapeia uma região com slide info (dyld_cache_mapping_and_slide_info). Igual a [mapOne]. */
    fun mapSlide(raf: RandomAccessFile, mp: DyldSharedCache.MappingSlide): Long {
        val backend = emulator.backend
        val rawSize = mp.size.toLong()
        backend.mem_map(mp.address.toLong(), align(rawSize), mp.initProt.toInt())
        val data = ByteArray(rawSize.toInt())
        raf.seek(mp.fileOffset.toLong())
        raf.readFully(data)
        backend.mem_write(mp.address.toLong(), data)
        return rawSize
    }

    /**
     * Acha (entre os arquivos do cache) a MappingSlide que contém [address] COM slide info,
     * mapeia-a e retorna o par (arquivo cache, mapping) — ou null. Permite mapear+rebasar só a
     * região DATA de uma dylib alvo.
     */
    fun mapSlideRegionContaining(files: List<File>, address: ULong): Pair<DyldSharedCache, DyldSharedCache.MappingSlide>? {
        for (f in files) {
            val cache = DyldSharedCache(f)
            val mp = cache.mappingsWithSlide.firstOrNull {
                it.slideInfoFileSize > 0uL && address >= it.address && address < it.address + it.size
            } ?: continue
            RandomAccessFile(f, "r").use { raf -> mapSlide(raf, mp) }
            return cache to mp
        }
        return null
    }

    private fun align(size: Long): Long = (size + pageAlign - 1) and (pageAlign - 1).inv()

    /**
     * Acha, entre os arquivos do cache, o mapping que contém [address] (vmaddr), mapeia-o e
     * retorna a [DyldSharedCache.Mapping] mapeada (ou null se não achar). Permite mapear SÓ a
     * região que cobre uma dylib alvo, em vez dos 3 GB.
     */
    fun mapRegionContaining(files: List<File>, address: ULong): DyldSharedCache.Mapping? {
        for (f in files) {
            val cache = DyldSharedCache(f)
            val mp = cache.mappings.firstOrNull {
                address >= it.address && address < it.address + it.size
            } ?: continue
            RandomAccessFile(f, "r").use { raf -> mapOne(raf, mp) }
            return mp
        }
        return null
    }
}
