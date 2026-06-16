package com.vortexdbg.ios.dsc

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Path B — resolve o VA de um símbolo exportado por uma dylib do cache lendo direto dos ARQUIVOS
 * (sem emulador / sem pré-mapear). Útil para descobrir o endereço de uma função antes de chamá-la
 * deixando o DscLazyMapper faltar o código dela em runtime. Constrói um índice vmaddr->arquivo/
 * offset a partir das mappings de todos os sub-caches.
 */
class DscFileResolver(mainCache: File) {

    private data class Region(val address: ULong, val size: ULong, val fileOffset: ULong, val file: File)

    private val regions: List<Region>
    private val images: Map<String, ULong>

    init {
        val files = DyldSharedCache.cacheFiles(mainCache)
        val list = ArrayList<Region>()
        for (f in files) {
            val cache = DyldSharedCache(f)
            for (mp in cache.mappings) list.add(Region(mp.address, mp.size, mp.fileOffset, f))
        }
        regions = list
        images = DyldSharedCache(mainCache).images().associate { it.path to it.loadAddress }
    }

    val imagePaths: Set<String> get() = images.keys

    /** Acha (arquivo, file-offset) que contém o [vmaddr] do cache. */
    private fun locate(vmaddr: ULong): Pair<File, Long>? {
        val r = regions.firstOrNull { vmaddr >= it.address && vmaddr < it.address + it.size } ?: return null
        return r.file to (r.fileOffset + (vmaddr - r.address)).toLong()
    }

    private fun readAt(file: File, offset: Long, len: Int): ByteArray =
        RandomAccessFile(file, "r").use { it.seek(offset); ByteArray(len).also { b -> it.readFully(b) } }

    // Load commands de dylib que contam para o "ordinal" de reexport (na ordem do arquivo).
    private val DYLIB_CMDS = setOf(0xc, 0x80000018.toInt(), 0x8000001f.toInt(), 0x80000023.toInt())

    private data class Parsed(
        val loadAddr: ULong,
        val linkeditVm: ULong,
        val linkeditFo: ULong,
        val trieOff: ULong,
        val trieSize: ULong,
        val deps: List<String>,  // 1-based no ordinal => deps[ordinal-1]
    )

    /** Parseia os load commands relevantes de [dylibPath] (linkedit, export trie e dependências). */
    private fun parseImage(dylibPath: String): Parsed? {
        val loadAddr = images[dylibPath] ?: return null
        val (mhFile, mhOff) = locate(loadAddr) ?: return null
        val head = ByteBuffer.wrap(readAt(mhFile, mhOff, 0x20)).order(ByteOrder.LITTLE_ENDIAN)
        val ncmds = head.getInt(16)
        val sizeofcmds = head.getInt(20)
        val cb = ByteBuffer.wrap(readAt(mhFile, mhOff + 0x20, sizeofcmds)).order(ByteOrder.LITTLE_ENDIAN)

        var linkeditVm = 0uL; var linkeditFo = 0uL
        var trieOff = 0uL; var trieSize = 0uL
        val deps = ArrayList<String>()
        var off = 0
        repeat(ncmds) {
            val cmd = cb.getInt(off)
            val cmdsize = cb.getInt(off + 4)
            when {
                cmd == 0x19 -> {
                    val name = ByteArray(16).also { cb.position(off + 8); cb.get(it) }
                        .let { b -> String(b, 0, b.indexOf(0).let { if (it < 0) 16 else it }, Charsets.US_ASCII) }
                    if (name == "__LINKEDIT") {
                        linkeditVm = cb.getLong(off + 24).toULong()
                        linkeditFo = cb.getLong(off + 40).toULong()
                    }
                }
                cmd == 0x80000033.toInt() -> {
                    trieOff = (cb.getInt(off + 8).toLong() and 0xffffffffL).toULong()
                    trieSize = (cb.getInt(off + 12).toLong() and 0xffffffffL).toULong()
                }
                cmd in DYLIB_CMDS -> {
                    val nameOff = cb.getInt(off + 8)  // dylib.name.offset, relativo ao início do cmd
                    val end = (off + cmdsize).coerceAtMost(cb.capacity())
                    var p = off + nameOff
                    val sb = StringBuilder()
                    while (p < end && cb.get(p).toInt() != 0) { sb.append(cb.get(p).toInt().toChar()); p++ }
                    deps.add(sb.toString())
                }
            }
            off += cmdsize
        }
        return Parsed(loadAddr, linkeditVm, linkeditFo, trieOff, trieSize, deps)
    }

    /**
     * VA do símbolo [symbol] exportado por [dylibPath]. Segue REEXPORTS (ordinal -> dylib dependente)
     * recursivamente; stub_and_resolver usa o offset do stub. Retorna null se não exportado.
     */
    fun resolve(dylibPath: String, symbol: String): ULong? = resolve(dylibPath, symbol, 0)

    private fun resolve(dylibPath: String, symbol: String, depth: Int): ULong? {
        if (depth > 16) return null
        val img = parseImage(dylibPath) ?: return null
        if (img.trieSize == 0uL) return null
        val trieVm = img.linkeditVm + (img.trieOff - img.linkeditFo)
        val (trieFile, trieFo) = locate(trieVm) ?: return null
        val trie = readAt(trieFile, trieFo, img.trieSize.toInt())
        val export = ExportTrie.resolve(trie, symbol) ?: return null

        if (export.isReexport) {
            val dep = img.deps.getOrNull(export.reexportOrdinal - 1) ?: return null
            val target = export.reexportName.ifEmpty { symbol }
            return resolve(dep, target, depth + 1)
        }
        return img.loadAddr + export.address.toULong() // normal ou stub_and_resolver (offset do stub)
    }
}
