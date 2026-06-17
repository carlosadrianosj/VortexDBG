package net.fornwall.jelf

import java.io.IOException
import java.util.Objects

open class ElfRelocation : Cloneable {

    private val objectSize: Int
    private val symtab: SymbolLocator

    @JvmField
    var offset: Long = 0
    @JvmField
    var info: Long = 0
    @JvmField
    var addend: Long = 0

    private val android: Boolean

    internal constructor(parser: ElfParser, offset: Long, entry_size: Long, symtab: SymbolLocator) {
        this.objectSize = parser.elfFile.objectSize.toInt()
        this.symtab = symtab
        this.android = false

        parser.seek(offset)

        if (parser.elfFile.objectSize == ElfFile.CLASS_32) {
            this.offset = parser.readInt().toLong() and 0xffffffffL
            this.info = parser.readInt().toLong()
            this.addend = if (entry_size >= 12) parser.readInt().toLong() else 0
        } else {
            this.offset = parser.readLong()
            this.info = parser.readLong()
            this.addend = if (entry_size >= 24) parser.readLong() else 0
        }
    }

    @Throws(CloneNotSupportedException::class)
    public override fun clone(): ElfRelocation {
        return super.clone() as ElfRelocation
    }

    internal constructor(objectSize: Int, symtab: SymbolLocator) {
        this.objectSize = objectSize
        this.symtab = symtab
        this.android = true
    }

    fun offset(): Long {
        return offset
    }

    private var symbol: ElfSymbol? = null

    @Throws(IOException::class)
    fun symbol(): ElfSymbol? {
        if (symbol == null) {
            symbol = symtab.getELFSymbol(sym())
        }
        return symbol
    }

    fun sym(): Int {
        val mask = if (objectSize == ElfFile.CLASS_32.toInt()) 8 else 32
        return (info shr mask).toInt()
    }

    fun type(): Int {
        val mask = if (objectSize == ElfFile.CLASS_32.toInt()) 0xffL else 0xffffffffL
        return (info and mask).toInt()
    }

    fun addend(): Long {
        return addend
    }

    fun isAndroid(): Boolean {
        return android
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o == null || javaClass != o.javaClass) return false
        val that = o as ElfRelocation
        return offset == that.offset &&
                info == that.info &&
                addend == that.addend
    }

    override fun hashCode(): Int {
        return Objects.hash(offset, info, addend)
    }

}
