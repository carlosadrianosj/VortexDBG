package net.fornwall.jelf

import java.io.IOException

interface SymbolLocator {

    @Throws(IOException::class)
    fun getELFSymbol(index: Int): ElfSymbol?

    @Throws(IOException::class)
    fun getELFSymbolByName(name: String?): ElfSymbol?

    @Throws(IOException::class)
    fun getELFSymbolByAddr(addr: Long): ElfSymbol?

}
