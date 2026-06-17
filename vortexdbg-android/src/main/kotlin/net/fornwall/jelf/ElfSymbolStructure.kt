package net.fornwall.jelf

import java.io.IOException

open class ElfSymbolStructure internal constructor(
    private val parser: ElfParser,
    private val offset: Long,
    private val entrySize: Int,
    private val stringTable: MemoizedObject<ElfStringTable>,
    private val hashTable: MemoizedObject<HashTable>?
) : SymbolLocator {

    /** Returns the symbol at the specified index. The ELF symbol at index 0 is the undefined symbol. */
    @Throws(IOException::class)
    override fun getELFSymbol(index: Int): ElfSymbol {
        return ElfSymbol(parser, offset + index.toLong() * entrySize, -1).setStringTable(stringTable.getValue())
    }

    @Throws(IOException::class)
    override fun getELFSymbolByAddr(addr: Long): ElfSymbol? {
        if (hashTable == null) {
            throw UnsupportedOperationException("hashTable is null")
        }
        return this.hashTable.getValue().findSymbolByAddress(this, addr)
    }

    @Throws(IOException::class)
    override fun getELFSymbolByName(name: String?): ElfSymbol? {
        if (hashTable == null) {
            return null
        }
        return hashTable.getValue().getSymbol(this, name)
    }

}
