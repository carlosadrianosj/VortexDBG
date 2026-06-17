package net.fornwall.jelf

import java.io.IOException

interface HashTable {

    /**
     * This method doesn't work every time and is unreliable. Use ELFSection.getELFSymbol(String) to retrieve symbols by
     * name. NOTE: since this method is currently broken it will always return null.
     */
    @Throws(IOException::class)
    fun getSymbol(symbolStructure: ElfSymbolStructure, symbolName: String?): ElfSymbol?

    @Throws(IOException::class)
    fun findSymbolByAddress(symbolStructure: ElfSymbolStructure, soaddr: Long): ElfSymbol?

    fun getNumBuckets(): Int

}
