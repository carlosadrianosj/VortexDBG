package net.fornwall.jelf

import java.io.IOException

class ElfHashTable(parser: ElfParser, offset: Long, length: Int) : HashTable {

    /**
     * Returns the ELFSymbol that has the specified name or null if no symbol with that name exists. NOTE: Currently
     * this method does not work and will always return null.
     */
    private val num_buckets: Int

    // These could probably be memoized.
    private val buckets: IntArray
    private val chains: IntArray

    init {
        parser.seek(offset)
        num_buckets = parser.readInt()
        val num_chains = parser.readInt()

        buckets = IntArray(num_buckets)
        chains = IntArray(num_chains)
        // Read the bucket data.
        for (i in 0 until num_buckets) {
            buckets[i] = parser.readInt()
        }

        // Read the chain data.
        for (i in 0 until num_chains) {
            chains[i] = parser.readInt()
        }

        // Make sure that the amount of bytes we were supposed to read
        // was what we actually read.
        val actual = num_buckets * 4 + num_chains * 4 + 8
        if (length != -1 && length != actual) {
            throw ElfException("Error reading string table (read " + actual + "bytes, expected to " + "read " + length + "bytes).")
        }
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

        var index = buckets[hash.toInt() % num_buckets]
        while (index != 0) {
            val symbol = symbolStructure.getELFSymbol(index)
            if (symbolName == symbol.getName()) {
                return symbol
            }
            index = chains[index]
        }
        return null
    }

    @Throws(IOException::class)
    override fun findSymbolByAddress(symbolStructure: ElfSymbolStructure, soaddr: Long): ElfSymbol? {
        // Search the library's symbol table for any defined symbol which
        // contains this address.
        for (i in chains.indices) {
            val symbol = symbolStructure.getELFSymbol(i)
            if (symbol.matches(soaddr)) {
                return symbol
            }
        }

        return null
    }

    override fun getNumBuckets(): Int {
        return num_buckets
    }

    companion object {
        private fun elf_hash(name: String): Long {
            var h: Long = 0
            var g: Long

            for (c in name.toCharArray()) {
                h = (h shl 4) + c.code
                g = h and 0xf0000000L
                h = h xor g
                h = h xor (g shr 24)
            }
            return h
        }
    }
}
