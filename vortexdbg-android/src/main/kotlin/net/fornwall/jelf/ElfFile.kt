package net.fornwall.jelf

import java.io.IOException
import java.nio.ByteBuffer

/**
 * An ELF (Executable and Linkable Format) file can be a relocatable, executable, shared or core file.
 *
 * <pre>
 * <a href="http://man7.org/linux/man-pages/man5/elf.5.html">man7</a>
 * <a href="http://en.wikipedia.org/wiki/Executable_and_Linkable_Format">wikipedia</a>
 * <a href="http://www.ibm.com/developerworks/library/l-dynamic-libraries/">ibm</a>
 * <a href="http://downloads.openwatcom.org/ftp/devel/docs/elf-64-gen.pdf">openwatcom</a>
 *
 * Elf64_Addr, Elf64_Off, Elf64_Xword, Elf64_Sxword: 8 bytes
 * Elf64_Word, Elf64_Sword: 4 bytes
 * Elf64_Half: 2 bytes
 * </pre>
 */
class ElfFile private constructor(buffer: ByteBuffer) {

    /** Byte identifying the size of objects, either [CLASS_32] or [CLASS_64]. */
    @JvmField
    val objectSize: Byte

    /**
     * Returns a byte identifying the data encoding of the processor specific data. This byte will be either
     * DATA_INVALID, DATA_LSB or DATA_MSB.
     */
    @JvmField
    val encoding: Byte

    /** Identifies the object file type. One of the FT_* constants in the class. */
    @JvmField
    val file_type: Short // Elf32_Half
    /** The required architecture. One of the ARCH_* constants in the class. */
    @JvmField
    val arch: Short // Elf32_Half
    /** Version */
    @JvmField
    val version: Int // Elf32_Word
    /**
     * Virtual address to which the system first transfers control. If there is no entry point for the file the value is
     * 0.
     */
    @JvmField
    val entry_point: Long // Elf32_Addr
    /** Program header table offset in bytes. If there is no program header table the value is 0. */
    @JvmField
    val ph_offset: Long // Elf32_Off
    /** Section header table offset in bytes. If there is no section header table the value is 0. */
    @JvmField
    val sh_offset: Long // Elf32_Off
    /** Processor specific flags. */
    @JvmField
    var flags: Int // Elf32_Word
    /** ELF header size in bytes. */
    @JvmField
    var eh_size: Short // Elf32_Half
    /** e_phentsize. Size of one entry in the file's program header table in bytes. All entries are the same size. */
    @JvmField
    val ph_entry_size: Short // Elf32_Half
    /** e_phnum. Number of [ElfSegment] entries in the program header table, 0 if no entries. */
    @JvmField
    val num_ph: Short // Elf32_Half
    /** Section header entry size in bytes. */
    @JvmField
    val sh_entry_size: Short // Elf32_Half
    /** Number of entries in the section header table, 0 if no entries. */
    @JvmField
    val num_sh: Short // Elf32_Half

    /**
     * Elf{32,64}_Ehdr#e_shstrndx. Index into the section header table associated with the section name string table.
     * SH_UNDEF if there is no section name string table.
     */
    private val sh_string_ndx: Int // Elf32_Half

    /** MemoizedObject array of section headers associated with this ELF file. */
    private val sectionHeaders: Array<MemoizedObject<ElfSection>>
    /** MemoizedObject array of program headers associated with this ELF file. */
    private val programHeaders: Array<MemoizedObject<ElfSegment>>

    /** Used to cache symbol table lookup. */
    private var symbolTableSection: ElfSection? = null
    /** Used to cache dynamic symbol table lookup. */
    private var dynamicSymbolTableSection: ElfSection? = null

    private var dynamicLinkSection: ElfSection? = null

    /**
     * Returns the section header at the specified index. The section header at index 0 is defined as being a undefined
     * section.
     */
    @Throws(ElfException::class, IOException::class)
    fun getSection(index: Int): ElfSection {
        return sectionHeaders[index].getValue()
    }

    /** Returns the section header string table associated with this ELF file. */
    @Throws(ElfException::class, IOException::class)
    fun getSectionNameStringTable(): ElfStringTable? {
        return getSection(sh_string_ndx).getStringTable()
    }

    /** Returns the string table associated with this ELF file. */
    @Throws(ElfException::class, IOException::class)
    fun getStringTable(): ElfStringTable? {
        return findStringTableWithName(ElfSection.STRING_TABLE_NAME)
    }

    /**
     * Returns the dynamic symbol table associated with this ELF file, or null if one does not exist.
     */
    @Throws(ElfException::class, IOException::class)
    fun getDynamicStringTable(): ElfStringTable? {
        return findStringTableWithName(ElfSection.DYNAMIC_STRING_TABLE_NAME)
    }

    @Throws(ElfException::class, IOException::class)
    private fun findStringTableWithName(tableName: String): ElfStringTable? {
        // Loop through the section header and look for a section
        // header with the name "tableName". We can ignore entry 0
        // since it is defined as being undefined.
        for (i in 1 until num_sh.toInt()) {
            val sh = getSection(i)
            if (tableName == sh.getName()) return sh.getStringTable()
        }
        return null
    }

    /** The [ElfSection.SHT_SYMTAB] section (of which there may be only one), if any. */
    @Throws(ElfException::class, IOException::class)
    fun getSymbolTableSection(): ElfSection? {
        return if (symbolTableSection != null) symbolTableSection else (findSectionSectionByType(ElfSection.SHT_SYMTAB).also { symbolTableSection = it })
    }

    /** The [ElfSection.SHT_DYNSYM] section (of which there may be only one), if any. */
    @Throws(ElfException::class, IOException::class)
    fun getDynamicSymbolTableSection(): ElfSection? {
        return if (dynamicSymbolTableSection != null) dynamicSymbolTableSection else (findSectionSectionByType(ElfSection.SHT_DYNSYM).also { dynamicSymbolTableSection = it })
    }

    /** The [ElfSection.SHT_DYNAMIC] section (of which there may be only one). Named ".dynamic". */
    @Throws(IOException::class)
    fun getDynamicLinkSection(): ElfSection? {
        return if (dynamicLinkSection != null) dynamicLinkSection else (findSectionSectionByType(ElfSection.SHT_DYNAMIC).also { dynamicLinkSection = it })
    }

    private var initArraySection: ElfSection? = null

    @Throws(IOException::class)
    fun getInitArraySection(): ElfSection? {
        return if (initArraySection != null) initArraySection else (findSectionSectionByType(ElfSection.SHT_INIT_ARRAY).also { initArraySection = it })
    }

    private var preInitArraySection: ElfSection? = null

    @Throws(IOException::class)
    fun getPreInitArraySection(): ElfSection? {
        return if (preInitArraySection != null) preInitArraySection else (findSectionSectionByType(ElfSection.SHT_PREINIT_ARRAY).also { preInitArraySection = it })
    }

    @Throws(ElfException::class, IOException::class)
    private fun findSectionSectionByType(type: Int): ElfSection? {
        for (i in 1 until num_sh.toInt()) {
            val sh = getSection(i)
            if (sh.type == type) return sh
        }
        return null
    }

    /** Returns the elf symbol with the specified name or null if one is not found. */
    @Throws(ElfException::class, IOException::class)
    fun getELFSymbol(symbolName: String?): ElfSymbol? {
        if (symbolName == null) return null

        // Check dynamic symbol table for symbol name.
        var sh = getDynamicSymbolTableSection()
        if (sh != null) {
            val numSymbols = sh.getNumberOfSymbols()
            var i = 0
            while (i < Math.ceil(numSymbols / 2.0)) {
                var symbol = sh.getELFSymbol(i)
                if (symbolName == symbol!!.getName()) {
                    return symbol
                } else if (symbolName == (sh.getELFSymbol(numSymbols - 1 - i).also { symbol = it })!!.getName()) {
                    return symbol
                }
                i++
            }
        }

        // Check symbol table for symbol name.
        sh = getSymbolTableSection()
        if (sh != null) {
            val numSymbols = sh.getNumberOfSymbols()
            var i = 0
            while (i < Math.ceil(numSymbols / 2.0)) {
                var symbol = sh.getELFSymbol(i)
                if (symbolName == symbol!!.getName()) {
                    return symbol
                } else if (symbolName == (sh.getELFSymbol(numSymbols - 1 - i).also { symbol = it })!!.getName()) {
                    return symbol
                }
                i++
            }
        }
        return null
    }

    /**
     * Returns the elf symbol with the specified address or null if one is not found. 'address' is relative to base of
     * shared object for .so's.
     */
    @Throws(ElfException::class, IOException::class)
    fun getELFSymbol(address: Long): ElfSymbol? {
        // Check dynamic symbol table for address.
        var symbol: ElfSymbol?
        var value: Long

        var sh = getDynamicSymbolTableSection()
        if (sh != null) {
            val numSymbols = sh.getNumberOfSymbols()
            for (i in 0 until numSymbols) {
                symbol = sh.getELFSymbol(i)
                value = symbol!!.value
                if (address >= value && address < value + symbol.size) return symbol
            }
        }

        // Check symbol table for symbol name.
        sh = getSymbolTableSection()
        if (sh != null) {
            val numSymbols = sh.getNumberOfSymbols()
            for (i in 0 until numSymbols) {
                symbol = sh.getELFSymbol(i)
                value = symbol!!.value
                if (address >= value && address < value + symbol.size) return symbol
            }
        }
        return null
    }

    @Throws(IOException::class)
    fun getProgramHeader(index: Int): ElfSegment {
        return programHeaders[index].getValue()
    }

    init {
        val ident = ByteArray(16)
        val parser = ElfParser(this, buffer)

        val bytesRead = parser.read(ident)
        if (bytesRead != ident.size) {
            throw ElfException("Error reading elf header (read " + bytesRead + "bytes - expected to read " + ident.size + "bytes)")
        }

        if (!(0x7f.toByte() == ident[0] && 'E'.code.toByte() == ident[1] && 'L'.code.toByte() == ident[2] && 'F'.code.toByte() == ident[3])) throw ElfException("Bad magic number for file")

        objectSize = ident[4]
        if (!(objectSize == CLASS_32 || objectSize == CLASS_64)) throw ElfException("Invalid object size class: $objectSize")
        encoding = ident[5]
        if (!(encoding == DATA_LSB || encoding == DATA_MSB)) throw ElfException("Invalid encoding: $encoding")
        val elfVersion = ident[6].toInt()
        if (elfVersion != 1) throw ElfException("Invalid elf version: $elfVersion")
        // ident[7]; // EI_OSABI, target operating system ABI
        // ident[8]; // EI_ABIVERSION, ABI version. Linux kernel (after at least 2.6) has no definition of it.
        // ident[9-15] // EI_PAD, currently unused.

        file_type = parser.readShort()
        arch = parser.readShort()
        version = parser.readInt()
        entry_point = parser.readIntOrLong()
        ph_offset = parser.readIntOrLong()
        sh_offset = parser.readIntOrLong()
        flags = parser.readInt()
        eh_size = parser.readShort()
        ph_entry_size = parser.readShort()
        num_ph = parser.readShort()
        sh_entry_size = parser.readShort()
        num_sh = parser.readShort()
        if (num_sh.toInt() == 0) {
            throw ElfException("e_shnum is SHN_UNDEF(0), which is not supported yet"
                    + " (the actual number of section header table entries is contained in the sh_size field of the section header at index 0)")
        }
        sh_string_ndx = parser.readShort().toInt() and 0xffff
        if (sh_string_ndx == /* SHN_XINDEX= */0xffff) {
            throw ElfException("e_shstrndx is SHN_XINDEX(0xffff), which is not supported yet"
                    + " (the actual index of the section name string table section is contained in the sh_link field of the section header at index 0)")
        }

        sectionHeaders = MemoizedObject.uncheckedArray(num_sh.toInt())
        for (i in 0 until num_sh.toInt()) {
            val sectionHeaderOffset = sh_offset + (i * sh_entry_size.toInt())
            sectionHeaders[i] = object : MemoizedObject<ElfSection>() {
                @Throws(ElfException::class)
                override fun computeValue(): ElfSection {
                    return ElfSection(this@ElfFile, parser, sectionHeaderOffset)
                }
            }
        }

        programHeaders = MemoizedObject.uncheckedArray(num_ph.toInt())
        for (i in 0 until num_ph.toInt()) {
            val programHeaderOffset = ph_offset + (i * ph_entry_size.toInt())
            programHeaders[i] = object : MemoizedObject<ElfSegment>() {
                override fun computeValue(): ElfSegment {
                    return ElfSegment(this@ElfFile, parser, programHeaderOffset)
                }
            }
        }
    }

    /** The interpreter specified by the [ElfSegment.PT_INTERP] program header, if any. */
    @Throws(IOException::class)
    fun getInterpreter(): String? {
        for (programHeader in programHeaders) {
            val ph = programHeader.getValue()
            if (ph.type == ElfSegment.PT_INTERP) return ph.getInterpreter()
        }
        return null
    }

    /**
     * Find the file offset from a virtual address by looking up the [ElfSegment] segment containing the
     * address and computing the resulting file offset.
     */
    @Throws(IOException::class)
    fun virtualMemoryAddrToFileOffset(address: Long): Long {
        for (i in 0 until this.num_ph.toInt()) {
            val ph = this.getProgramHeader(i)
            if (address >= ph.virtual_address && address < (ph.virtual_address + ph.mem_size)) {
                val relativeOffset = address - ph.virtual_address
                if (relativeOffset >= ph.file_size) {
                    throw ElfException("Can not convert virtual memory address " + java.lang.Long.toHexString(address) + " to file offset -" + " found segment " + ph + " but address maps to memory outside file range")
                }
                return ph.offset + relativeOffset
            }
        }
        throw ElfException("Cannot find segment for address 0x" + java.lang.Long.toHexString(address))
    }

    companion object {
        /** Relocatable file type. A possible value of [file_type]. */
        const val FT_REL = 1
        /** Executable file type. A possible value of [file_type]. */
        const val FT_EXEC = 2
        /** Shared object file type. A possible value of [file_type]. */
        const val FT_DYN = 3
        /** Core file file type. A possible value of [file_type]. */
        const val FT_CORE = 4

        /** 32-bit objects. */
        const val CLASS_32: Byte = 1
        /** 64-bit objects. */
        const val CLASS_64: Byte = 2

        /** LSB data encoding. */
        const val DATA_LSB: Byte = 1
        /** MSB data encoding. */
        const val DATA_MSB: Byte = 2

        /** No architecture type. */
        const val ARCH_NONE = 0
        /** AT&amp;T architecture type. */
        const val ARCH_ATT = 1
        /** SPARC architecture type. */
        const val ARCH_SPARC = 2
        /** Intel 386 architecture type. */
        const val ARCH_i386 = 3
        /** Motorola 68000 architecture type. */
        const val ARCH_68k = 4
        /** Motorola 88000 architecture type. */
        const val ARCH_88k = 5
        /** Intel 860 architecture type. */
        const val ARCH_i860 = 7
        /** MIPS architecture type. */
        const val ARCH_MIPS = 8
        const val ARCH_ARM = 0x28
        const val ARCH_X86_64 = 0x3E
        const val ARCH_AARCH64 = 0xB7

        @JvmStatic
        @Throws(ElfException::class)
        fun fromBuffer(buffer: ByteBuffer): ElfFile {
            return ElfFile(buffer)
        }
    }
}
