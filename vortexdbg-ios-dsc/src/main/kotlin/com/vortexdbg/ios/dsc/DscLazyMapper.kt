package com.vortexdbg.ios.dsc

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.EventMemHook
import java.io.File
import java.io.RandomAccessFile
import unicorn.UnicornConst

/**
 * Path B [5/2b] — mapeamento LAZY do dyld_shared_cache via hook de memória não-mapeada. Em vez
 * de mapear ~3 GB (ou mapear regiões inteiras por segmento, estourando RAM), instala um hook em
 * UC_HOOK_MEM_{READ,WRITE,FETCH}_UNMAPPED: quando o código emulado toca um endereço do cache que
 * ainda não está mapeado, faltamos a PÁGINA (0x4000) sob demanda — lendo do arquivo de cache no
 * file-offset certo — e, se a região tiver slide info V5, rebaseamos só aquela página. Retornar
 * true faz o Unicorn retentar o acesso. É o "loader hook": resolve tudo dentro do cache.
 */
class DscLazyMapper(private val emulator: Emulator<*>, mainCache: File) {

    private val pageSize = 0x4000L  // granularidade da slide info V5 (16 KB)

    private data class Region(
        val address: Long,
        val size: Long,
        val fileOffset: Long,
        val prot: Int,
        val file: File,
        val slide: DyldSharedCache.SlideInfo5?,
    )

    private val regions: List<Region>
    private val rafs = HashMap<File, RandomAccessFile>()
    @Volatile var pagesMapped = 0; private set
    @Volatile var pagesRebased = 0; private set

    init {
        val list = ArrayList<Region>()
        for (f in DyldSharedCache.cacheFiles(mainCache)) {
            val cache = DyldSharedCache(f)
            val mws = cache.mappingsWithSlide
            if (mws.isNotEmpty()) {
                for (mp in mws) {
                    val si = if (mp.slideInfoFileSize > 0uL) cache.slideInfo5(mp) else null
                    list.add(Region(mp.address.toLong(), mp.size.toLong(), mp.fileOffset.toLong(), mp.initProt.toInt(), f, si))
                }
            } else {
                for (mp in cache.mappings) {
                    list.add(Region(mp.address.toLong(), mp.size.toLong(), mp.fileOffset.toLong(), mp.initProt.toInt(), f, null))
                }
            }
        }
        regions = list

        emulator.backend.hook_add_new(object : EventMemHook {
            override fun hook(backend: Backend, address: Long, size: Int, value: Long, user: Any?, type: EventMemHook.UnmappedType): Boolean =
                mapFault(address)
            override fun onAttach(unHook: com.vortexdbg.arm.backend.UnHook) {}
            override fun detach() {}
        }, UnicornConst.UC_HOOK_MEM_READ_UNMAPPED or UnicornConst.UC_HOOK_MEM_WRITE_UNMAPPED or UnicornConst.UC_HOOK_MEM_FETCH_UNMAPPED, null)
    }

    /** Falta a página do cache que cobre [address]. Retorna true se mapeou (acesso pode retentar). */
    private fun mapFault(address: Long): Boolean {
        val region = regions.firstOrNull { address >= it.address && address < it.address + it.size } ?: return false
        val pageIndex = (address - region.address) / pageSize
        val pageAddr = region.address + pageIndex * pageSize
        val avail = region.size - pageIndex * pageSize
        val chunk = minOf(pageSize, avail)

        val backend = emulator.backend
        backend.mem_map(pageAddr, pageSize, region.prot)
        val raf = rafs.getOrPut(region.file) { RandomAccessFile(region.file, "r") }
        val data = ByteArray(chunk.toInt())
        raf.seek(region.fileOffset + pageIndex * pageSize)
        raf.readFully(data)
        backend.mem_write(pageAddr, data)
        pagesMapped++

        val si = region.slide
        if (si != null && pageIndex < si.pageStarts.size) {
            pagesRebased += if (SlideRebaser.rebasePage(emulator, pageAddr, si.valueAdd.toLong(), si.pageStarts[pageIndex.toInt()]) > 0) 1 else 0
        }
        return true
    }

    fun close() {
        rafs.values.forEach { it.close() }
        rafs.clear()
    }
}
