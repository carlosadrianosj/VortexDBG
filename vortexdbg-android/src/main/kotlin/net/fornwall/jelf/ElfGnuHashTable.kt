package net.fornwall.jelf

import java.io.IOException

internal class ElfGnuHashTable(parser: ElfParser, offset: Long) : HashTable {

    private interface HashChain {
        fun chain(index: Int): Int
    }

    private val nbucket: Int
    private val maskwords: Int
    private val shift2: Int

    private val bloom_filters: LongArray
    private val buckets: IntArray
    private val chains: HashChain

    private val bloom_mask_bits: Int

    init {
        parser.seek(offset)
        nbucket = parser.readInt()
        val symndx = parser.readInt()
        val gnu_maskwords_ = parser.readInt()
        shift2 = parser.readInt()

        bloom_filters = LongArray(gnu_maskwords_)
        for (i in bloom_filters.indices) {
            bloom_filters[i] = parser.readIntOrLong()
        }

        buckets = IntArray(nbucket)
        for (i in 0 until nbucket) {
            buckets[i] = parser.readInt()
        }

        val chain_base = offset + 16 + gnu_maskwords_.toLong() * (if (parser.elfFile.objectSize == ElfFile.CLASS_32) 4 else 8) + nbucket * 4L - symndx * 4L
        chains = object : HashChain {
            override fun chain(index: Int): Int {
                parser.seek(chain_base + index * 4L)
                return parser.readInt()
            }
        }

        maskwords = gnu_maskwords_ - 1
        bloom_mask_bits = if (parser.elfFile.objectSize == ElfFile.CLASS_32) 32 else 64
    }

    /**
     * This method doesn't work every time and is unreliable. Use ELFSection.getELFSymbol(String) to retrieve symbols by
     * name. NOTE: since this method is currently broken it will always return null.
     */
    @Throws(IOException::class)
    override fun getSymbol(symbolStructure: ElfSymbolStructure, symbolName: String?): ElfSymbol? {
        if (symbolName == null) {
            return null
        }

        val hash = elf_hash(symbolName)
        val h2 = hash shr shift2

        val word_num = (hash / bloom_mask_bits) and maskwords.toLong()
        val bloom_word = bloom_filters[word_num.toInt()]

        // test against bloom filter
        if ((1L and (bloom_word shr (hash % bloom_mask_bits).toInt()) and (bloom_word shr (h2 % bloom_mask_bits).toInt())) == 0L) {
            return null
        }

        // bloom test says "probably yes"...
        var n = buckets[(hash % nbucket).toInt()]
        if (n == 0) {
            return null
        }

        do {
            val symbol = symbolStructure.getELFSymbol(n)
            if (symbolName == symbol.getName()) {
                return symbol
            }
        } while ((chains.chain(n++) and 1) == 0)

        return null
    }

    @Throws(IOException::class)
    override fun findSymbolByAddress(symbolStructure: ElfSymbolStructure, soaddr: Long): ElfSymbol? {
        for (i in 0 until nbucket) {
            var n = buckets[i]

            if (n == 0) {
                continue
            }

            do {
                val symbol = symbolStructure.getELFSymbol(n)
                if (symbol.matches(soaddr)) {
                    return symbol
                }
            } while ((chains.chain(n++) and 1) == 0)
        }

        return null
    }

    override fun getNumBuckets(): Int {
        return nbucket
    }

    companion object {
        private fun elf_hash(name: String): Long {
            var h: Long = 5381

            for (c in name.toCharArray()) {
                h += (h shl 5) + c.code // h*33 + c = h + h * 32 + c = h + h << 5 + c
            }
            return h and 0xffffffffL
        }
    }
}
