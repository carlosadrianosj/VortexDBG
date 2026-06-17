package net.fornwall.jelf

import java.io.IOException

/**
 * Class corresponding to the Elf32_Shdr/Elf64_Shdr struct.
 *
 *
 * An object file's section header table lets one locate all the file's sections.
 */
class ElfSection
/** Reads the section header information located at offset. */
internal constructor(elfFile: ElfFile, parser: ElfParser, offset: Long) : SymbolLocator {

    /** Index into the section header string table which gives the name of the section. */
    @JvmField
    val name_ndx: Int // Elf32_Word or Elf64_Word - 4 bytes in both.
    /** Section content and semantics. */
    @JvmField
    val type: Int // Elf32_Word or Elf64_Word - 4 bytes in both.
    /** Flags. */
    @JvmField
    val flags: Long // Elf32_Word or Elf64_Xword.
    /**
     * sh_addr. If the section will be in the memory image of a process this will be the address at which the first byte
     * of section will be loaded. Otherwise, this value is 0.
     */
    @JvmField
    val address: Long // Elf32_Addr
    /** Offset from beginning of file to first byte of the section. */
    @JvmField
    val section_offset: Long // Elf32_Off
    /** Size in bytes of the section. TYPE_NOBITS is a special case. */
    @JvmField
    val size: Long // Elf32_Word
    /** Section header table index link. */
    @JvmField
    val link: Int // Elf32_Word or Elf64_Word - 4 bytes in both.
    /** Extra information determined by the section type. */
    @JvmField
    val info: Int // Elf32_Word or Elf64_Word - 4 bytes in both.
    /** Address alignment constraints for the section. */
    @JvmField
    val address_alignment: Long // Elf32_Word
    /** Size of a fixed-size entry, 0 if none. */
    @JvmField
    val entry_size: Long // Elf32_Word

    private var symbols: Array<MemoizedObject<ElfSymbol>>? = null
    private var stringTable: MemoizedObject<ElfStringTable>? = null
    private var hashTable: MemoizedObject<ElfHashTable>? = null
    private var relocations: Array<MemoizedObject<ElfRelocation>>? = null
    /** For the [SHT_DYNAMIC] ".dynamic" structure. */
    private var dynamicStructure: MemoizedObject<ElfDynamicStructure>? = null
    private var initArray: MemoizedObject<ElfInitArray>? = null
    private var preInitArray: MemoizedObject<ElfInitArray>? = null

    private val elfHeader: ElfFile

    init {
        this.elfHeader = parser.elfFile
        parser.seek(offset)

        name_ndx = parser.readInt()
        type = parser.readInt()
        flags = parser.readIntOrLong()
        address = parser.readIntOrLong()
        section_offset = parser.readIntOrLong()
        size = parser.readIntOrLong()
        link = parser.readInt()
        info = parser.readInt()
        address_alignment = parser.readIntOrLong()
        entry_size = parser.readIntOrLong()

        when (type) {
            SHT_SYMTAB, SHT_DYNSYM -> {
                val num_entries = (size / entry_size).toInt()
                symbols = MemoizedObject.uncheckedArray(num_entries)
                for (i in 0 until num_entries) {
                    val symbolOffset = section_offset + (i * entry_size)
                    symbols!![i] = object : MemoizedObject<ElfSymbol>() {
                        override fun computeValue(): ElfSymbol {
                            return ElfSymbol(parser, symbolOffset, type)
                        }
                    }
                }
            }
            SHT_STRTAB -> {
                stringTable = object : MemoizedObject<ElfStringTable>() {
                    @Throws(IOException::class)
                    override fun computeValue(): ElfStringTable {
                        return ElfStringTable(parser, section_offset, size.toInt())
                    }
                }
            }
            SHT_HASH -> {
                hashTable = object : MemoizedObject<ElfHashTable>() {
                    override fun computeValue(): ElfHashTable {
                        return ElfHashTable(parser, section_offset, size.toInt())
                    }
                }
            }
            SHT_DYNAMIC -> {
                dynamicStructure = object : MemoizedObject<ElfDynamicStructure>() {
                    @Throws(ElfException::class, IOException::class)
                    override fun computeValue(): ElfDynamicStructure {
                        return ElfDynamicStructure(elfFile, parser, section_offset, size.toInt())
                    }
                }
            }
            SHT_RELA, SHT_REL -> {
                val num_entries = (size / entry_size).toInt()
                relocations = MemoizedObject.uncheckedArray(num_entries)
                for (i in 0 until num_entries) {
                    val relocationOffset = section_offset + (i * entry_size)
                    relocations!![i] = object : MemoizedObject<ElfRelocation>() {
                        @Throws(IOException::class)
                        override fun computeValue(): ElfRelocation {
                            return ElfRelocation(parser, relocationOffset, entry_size, parser.elfFile.getSection(link))
                        }
                    }
                }
            }
            SHT_INIT_ARRAY -> {
                initArray = object : MemoizedObject<ElfInitArray>() {
                    @Throws(ElfException::class)
                    override fun computeValue(): ElfInitArray {
                        return ElfInitArray(parser, section_offset, size.toInt())
                    }
                }
            }
            SHT_PREINIT_ARRAY -> {
                preInitArray = object : MemoizedObject<ElfInitArray>() {
                    @Throws(ElfException::class)
                    override fun computeValue(): ElfInitArray {
                        return ElfInitArray(parser, section_offset, size.toInt())
                    }
                }
            }
            SHT_NULL, SHT_PROGBITS, SHT_SHLIB, SHT_NOTE, SHT_NOBITS -> {
            }
            else -> {
            }
        }
    }

    @Throws(IOException::class)
    fun getInitArray(): ElfInitArray {
        return initArray!!.getValue()
    }

    @Throws(IOException::class)
    fun getPreInitArray(): ElfInitArray {
        return preInitArray!!.getValue()
    }

    /** Returns the number of symbols in this section or 0 if none. */
    fun getNumberOfSymbols(): Int {
        return if (symbols != null) symbols!!.size else 0
    }

    /** Returns the symbol at the specified index. The ELF symbol at index 0 is the undefined symbol. */
    @Throws(IOException::class)
    override fun getELFSymbol(index: Int): ElfSymbol {
        return symbols!![index].getValue()
    }

    @Throws(IOException::class)
    override fun getELFSymbolByName(name: String?): ElfSymbol? {
        var i = 0
        val m = getNumberOfSymbols()
        while (i < m) {
            val symbol = getELFSymbol(i)
            if (name != null && name == symbol.getName()) {
                return symbol
            }
            i++
        }
        return null
    }

    @Throws(IOException::class)
    override fun getELFSymbolByAddr(addr: Long): ElfSymbol? {
        var i = 0
        val m = getNumberOfSymbols()
        while (i < m) {
            val symbol = getELFSymbol(i)
            if (addr >= symbol.value && addr < symbol.value + symbol.size) {
                return symbol
            }
            i++
        }
        return null
    }

    /** Returns the number of relocations in this section or 0 if none. */
    fun getNumberOfRelocations(): Int {
        return if (relocations != null) relocations!!.size else 0
    }

    /** Returns the relocation at the specified index. */
    @Throws(IOException::class)
    fun getELFRelocation(index: Int): ElfRelocation {
        return relocations!![index].getValue()
    }

    /** Returns the string table for this section or null if one does not exist. */
    @Throws(IOException::class)
    fun getStringTable(): ElfStringTable? {
        return if (stringTable != null) stringTable!!.getValue() else null
    }

    @Throws(IOException::class)
    fun getDynamicSection(): ElfDynamicStructure? {
        return if (dynamicStructure != null) dynamicStructure!!.getValue() else null
    }

    /**
     * Returns the hash table for this section or null if one does not exist. NOTE: currently the ELFHashTable does not
     * work and this method will always return null.
     */
    @Throws(IOException::class)
    fun getHashTable(): ElfHashTable? {
        return if (hashTable != null) hashTable!!.getValue() else null
    }

    /** Returns the name of the section or null if the section has no name. */
    @Throws(IOException::class)
    fun getName(): String? {
        if (name_ndx == 0) return null
        val tbl = elfHeader.getSectionNameStringTable()
        return tbl!!.get(name_ndx)
    }

    override fun toString(): String {
        try {
            return "ElfSectionHeader[name=" + getName() + ", type=0x" + java.lang.Long.toHexString(type.toLong()) + "]"
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    companion object {
        /**
         * Marks the section header as inactive; it does not have an associated section. Other members of the section header
         * have undefined values.
         */
        const val SHT_NULL = 0
        /** Section holds information defined by the program. */
        const val SHT_PROGBITS = 1
        /**
         * Section holds symbol table information for link editing.
         */
        const val SHT_SYMTAB = 2
        /** Section holds string table information. */
        const val SHT_STRTAB = 3
        /** Section holds relocation entries with explicit addends. */
        const val SHT_RELA = 4
        /** Section holds symbol hash table. */
        const val SHT_HASH = 5
        /**
         * Section holds information for dynamic linking. Only one per ELF file.
         */
        const val SHT_DYNAMIC = 6
        /** Section holds information that marks the file. */
        const val SHT_NOTE = 7
        /** Section occupies no space but resembles TYPE_PROGBITS. */
        const val SHT_NOBITS = 8
        /** Section holds relocation entries without explicit addends. */
        const val SHT_REL = 9
        /** Section is reserved but has unspecified semantics. */
        const val SHT_SHLIB = 10
        /** Section holds a minimum set of dynamic linking symbols. Only one per ELF file. */
        const val SHT_DYNSYM = 11
        const val SHT_INIT_ARRAY = 14
        const val SHT_FINI_ARRAY = 15
        const val SHT_PREINIT_ARRAY = 16
        const val SHT_GROUP = 17
        const val SHT_SYMTAB_SHNDX = 18

        const val SHT_GNU_verdef = 0x6ffffffd
        const val SHT_GNU_verneed = 0x6ffffffe
        const val SHT_GNU_versym = 0x6fffffff

        /** Lower bound of the range of indexes reserved for operating system-specific semantics. */
        const val SHT_LOOS = 0x60000000
        /** Upper bound of the range of indexes reserved for operating system-specific semantics. */
        const val SHT_HIOS = 0x6fffffff
        /** Lower bound of the range of indexes reserved for processor-specific semantics. */
        const val SHT_LOPROC = 0x70000000
        /** Upper bound of the range of indexes reserved for processor-specific semantics. */
        const val SHT_HIPROC = 0x7fffffff
        /** Lower bound of the range of indexes reserved for application programs. */
        @JvmField val SHT_LOUSER = 0x80000000.toInt()
        /** Upper bound of the range of indexes reserved for application programs. */
        @JvmField val SHT_HIUSER = 0xffffffff.toInt()

        /** Flag informing that this section contains data that should be writable during process execution. */
        const val FLAG_WRITE = 0x1
        /** Flag informing that section occupies memory during process execution. */
        const val FLAG_ALLOC = 0x2
        /** Flag informing that section contains executable machine instructions. */
        const val FLAG_EXEC_INSTR = 0x4
        /** Flag informing that all the bits in the mask are reserved for processor specific semantics. */
        @JvmField val FLAG_MASK = 0xf0000000.toInt()

        /** Section header name identifying the section as a string table. */
        const val STRING_TABLE_NAME = ".strtab"
        /** Section header name identifying the section as a dynamic string table. */
        const val DYNAMIC_STRING_TABLE_NAME = ".dynstr"
    }
}
