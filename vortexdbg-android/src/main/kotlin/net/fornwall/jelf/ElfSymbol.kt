package net.fornwall.jelf

import java.io.IOException

/**
 * Class corresponding to the Elf32_Sym/Elf64_Sym struct.
 */
class ElfSymbol internal constructor(parser: ElfParser, offset: Long, section_type: Int) {

    /**
     * Index into the symbol string table that holds the character representation of the symbols. 0 means the symbol has
     * no character name.
     */
    private val name_ndx: Int // Elf32_Word
    /** Value of the associated symbol. This may be a relativa address for .so or absolute address for other ELFs. */
    @JvmField
    val value: Long // Elf32_Addr
    /** Size of the symbol. 0 if the symbol has no size or the size is unknown. */
    @JvmField
    val size: Long // Elf32_Word
    /** Specifies the symbol type and binding attributes. */
    private val info: Short // unsigned char
    /** Currently holds the value of 0 and has no meaning. */
    @JvmField
    val other: Short // unsigned char
    /**
     * Index to the associated section header. This value will need to be read as an unsigned short if we compare it to
     * ELFSectionHeader.NDX_LORESERVE and ELFSectionHeader.NDX_HIRESERVE.
     */
    @JvmField
    val section_header_ndx: Short // Elf32_Half

    private val section_type: Int

    /** Offset from the beginning of the file to this symbol. */
    @JvmField
    val offset: Long

    private val elfHeader: ElfFile

    init {
        this.elfHeader = parser.elfFile
        parser.seek(offset)
        this.offset = offset
        if (parser.elfFile.objectSize == ElfFile.CLASS_32) {
            name_ndx = parser.readInt()
            value = parser.readInt().toLong()
            size = parser.readInt().toLong()
            info = parser.readUnsignedByte()
            other = parser.readUnsignedByte()
            section_header_ndx = parser.readShort()
        } else {
            name_ndx = parser.readInt()
            info = parser.readUnsignedByte()
            other = parser.readUnsignedByte()
            section_header_ndx = parser.readShort()
            value = parser.readLong()
            size = parser.readLong()
        }

        this.section_type = section_type

        when (getType()) {
            STT_NOTYPE.toInt(), STT_OBJECT.toInt(), STT_FUNC.toInt(), STT_SECTION.toInt(), STT_FILE.toInt(), STT_LOPROC.toInt(), STT_HIPROC.toInt() -> {
            }
            else -> {
            }
        }
    }

    fun matches(soaddr: Long): Boolean {
        val value = this.value and 1L.inv()
        return section_header_ndx.toInt() != SHN_UNDEF &&
                soaddr >= value &&
                soaddr < value + size
    }

    private var stringTable: ElfStringTable? = null

    fun setStringTable(stringTable: ElfStringTable?): ElfSymbol {
        this.stringTable = stringTable
        return this
    }

    /** Returns the binding for this symbol. */
    fun getBinding(): Int {
        return info.toInt() shr 4
    }

    /** Returns the symbol type. */
    fun getType(): Int {
        return info.toInt() and 0x0F
    }

    /** Returns the name of the symbol or null if the symbol has no name. */
    @Throws(ElfException::class, IOException::class)
    fun getName(): String? {
        // Check to make sure this symbol has a name.
        if (name_ndx == 0)
            return null

        // Retrieve the name of the symbol from the correct string table.
        var symbol_name: String? = null
        if (stringTable != null) {
            symbol_name = stringTable!!.get(name_ndx)
        } else if (section_type == ElfSection.SHT_SYMTAB) {
            symbol_name = elfHeader.getStringTable()!!.get(name_ndx)
        } else if (section_type == ElfSection.SHT_DYNSYM) {
            symbol_name = elfHeader.getDynamicStringTable()!!.get(name_ndx)
        }
        return symbol_name
    }

    fun isUndef(): Boolean {
        return section_header_ndx.toInt() == SHN_UNDEF
    }

    override fun toString(): String {
        val typeString: String
        when (getType()) {
            STT_NOTYPE.toInt(), STT_OBJECT.toInt() -> typeString = "object"
            STT_FUNC.toInt() -> typeString = "function"
            STT_SECTION.toInt() -> typeString = "section"
            STT_FILE.toInt() -> typeString = "file"
            STT_LOPROC.toInt() -> typeString = "loproc"
            STT_HIPROC.toInt() -> typeString = "hiproc"
            else -> typeString = "???"
        }

        try {
            return "ElfSymbol[name=" + getName() + ", type=" + typeString + ", size=" + size + "]"
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    companion object {
        private const val SHN_UNDEF = 0

        /** Binding specifying that local symbols are not visible outside the object file that contains its definition. */
        const val BINDING_LOCAL = 0
        /** Binding specifying that global symbols are visible to all object files being combined. */
        const val BINDING_GLOBAL = 1
        /** Binding specifying that the symbol resembles a global symbol, but has a lower precedence. */
        const val BINDING_WEAK = 2
        /** Lower bound binding values reserved for processor specific semantics. */
        const val BINDING_LOPROC = 13
        /** Upper bound binding values reserved for processor specific semantics. */
        const val BINDING_HIPROC = 15

        /** Type specifying that the symbol is unspecified. */
        const val STT_NOTYPE: Byte = 0
        /** Type specifying that the symbol is associated with an object. */
        const val STT_OBJECT: Byte = 1
        /** Type specifying that the symbol is associated with a function or other executable code. */
        const val STT_FUNC: Byte = 2
        /**
         * Type specifying that the symbol is associated with a section. Symbol table entries of this type exist for
         * relocation and normally have the binding BINDING_LOCAL.
         */
        const val STT_SECTION: Byte = 3
        /** Type defining that the symbol is associated with a file. */
        const val STT_FILE: Byte = 4
        /** The symbol labels an uninitialized common block. */
        const val STT_COMMON: Byte = 5
        /** The symbol specifies a Thread-Local Storage entity. */
        const val STT_TLS: Byte = 6

        /** Lower bound for range reserved for operating system-specific semantics. */
        const val STT_LOOS: Byte = 10
        /** Upper bound for range reserved for operating system-specific semantics. */
        const val STT_HIOS: Byte = 12
        /** Lower bound for range reserved for processor-specific semantics. */
        const val STT_LOPROC: Byte = 13
        /** Upper bound for range reserved for processor-specific semantics. */
        const val STT_HIPROC: Byte = 15
    }
}
