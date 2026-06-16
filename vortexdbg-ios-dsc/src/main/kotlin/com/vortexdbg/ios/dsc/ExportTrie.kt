package com.vortexdbg.ios.dsc

/**
 * Path B — parser da export trie do Mach-O (LC_DYLD_EXPORTS_TRIE). É um trie ULEB128:
 * cada nó tem terminal_size; se !=0, é um símbolo exportado (flags + address-offset, ambos
 * uleb, offset relativo à base da imagem). Depois vem child_count(u8) e, por filho, a aresta
 * (cstring) + offset(uleb) do nó-filho. Resolver = descer casando o prefixo do nome.
 */
object ExportTrie {

    const val FLAG_REEXPORT = 0x08L
    const val FLAG_STUB_AND_RESOLVER = 0x10L

    /**
     * Resultado terminal da trie. Para export normal/stub: [address] é o offset relativo à base
     * (no stub_and_resolver é o offset do stub). Para REEXPORT: [reexportOrdinal] (1-based, índice
     * na lista de dylibs dependentes) + [reexportName] (vazio = mesmo nome).
     */
    data class Export(
        val flags: Long,
        val address: Long,
        val reexportOrdinal: Int = -1,
        val reexportName: String = "",
    ) {
        val isReexport get() = flags and FLAG_REEXPORT != 0L
    }

    /** Resolve [symbol] na trie [trie] (bytes crus). Retorna o terminal, ou null. */
    fun resolve(trie: ByteArray, symbol: String): Export? {
        var nodeOff = 0
        var matched = ""   // prefixo já consumido
        while (true) {
            val (termSize, afterTerm) = uleb(trie, nodeOff)
            if (matched == symbol && termSize > 0L) {
                val (flags, p1) = uleb(trie, afterTerm)
                if (flags and FLAG_REEXPORT != 0L) {
                    val (ordinal, p2) = uleb(trie, p1)
                    val start = p2
                    var p = p2
                    while (trie[p].toInt() != 0) p++
                    val name = String(trie, start, p - start, Charsets.US_ASCII)
                    return Export(flags, 0, ordinal.toInt(), name)
                }
                val (addr, _) = uleb(trie, p1)
                return Export(flags, addr)
            }
            var p = afterTerm + termSize.toInt()
            val childCount = trie[p].toInt() and 0xFF
            p += 1
            var next: Pair<String, Int>? = null
            for (i in 0 until childCount) {
                val start = p
                while (trie[p].toInt() != 0) p++
                val edge = String(trie, start, p - start, Charsets.US_ASCII)
                p++ // pula NUL
                val (childOff, p2) = uleb(trie, p)
                p = p2
                if (next == null && symbol.startsWith(matched + edge)) {
                    next = (matched + edge) to childOff.toInt()
                }
            }
            val n = next ?: return null
            matched = n.first
            nodeOff = n.second
        }
    }

    /** Lê um ULEB128 a partir de [pos]; retorna (valor, próxima posição). */
    private fun uleb(b: ByteArray, pos: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var p = pos
        while (true) {
            val x = b[p].toInt() and 0xFF
            p++
            result = result or ((x.toLong() and 0x7F) shl shift)
            if (x and 0x80 == 0) break
            shift += 7
        }
        return result to p
    }
}
