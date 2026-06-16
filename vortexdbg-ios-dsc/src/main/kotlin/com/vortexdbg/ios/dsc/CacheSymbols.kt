package com.vortexdbg.ios.dsc

import com.vortexdbg.Emulator
import java.io.File

/**
 * Path B — resolve símbolos EXPORTADOS por uma dylib do cache, via a export trie dela.
 * Pressupõe que o __TEXT da dylib já está mapeado (os load commands moram nele). Converte o
 * file-offset da trie (LC_DYLD_EXPORTS_TRIE) para vmaddr usando a relação do __LINKEDIT, mapeia
 * uma janela com a trie, parseia e devolve o VA do símbolo (loadAddress + offset exportado).
 */
class CacheSymbols(private val emulator: Emulator<*>, private val files: List<File>) {

    private val mapper = DscMapper(emulator)
    // A trie de cada dylib é lida/mapeada uma vez só (janela do linkedit reusada entre símbolos).
    private val trieCache = HashMap<ULong, ByteArray?>()

    /** VA do símbolo [symbol] exportado pela dylib carregada em [loadAddress], ou null. */
    fun exportedSymbol(loadAddress: ULong, symbol: String): ULong? {
        val trie = trieCache.getOrPut(loadAddress) { loadTrie(loadAddress) } ?: return null
        val export = ExportTrie.resolve(trie, symbol) ?: return null
        return loadAddress + export.address.toULong()
    }

    private fun loadTrie(loadAddress: ULong): ByteArray? {
        val info = CachedMacho.parse(emulator, loadAddress)
        if (info.exportsTrieSize == 0uL) return null
        val linkedit = info.segment("__LINKEDIT") ?: return null
        // file-offset da trie -> vmaddr: a trie vive dentro do __LINKEDIT (mesmo delta arquivo↔vm).
        val trieVm = linkedit.vmaddr + (info.exportsTrieOffset - linkedit.fileOffset)
        mapper.mapWindow(files, trieVm, info.exportsTrieSize.toLong()) ?: return null
        return emulator.backend.mem_read(trieVm.toLong(), info.exportsTrieSize.toLong())
    }
}
