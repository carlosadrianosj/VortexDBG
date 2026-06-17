package net.fornwall.jelf

import java.io.IOException
import java.nio.ByteBuffer

/**
 * Class corresponding to the Elf32_Phdr/Elf64_Phdr struct.
 *
 * An executable or shared object file's program header table is an array of structures, each describing a segment or
 * other information the system needs to prepare the program for execution. An object file segment contains one or more
 * sections. Program headers are meaningful only for executable and shared object files. A file specifies its own
 * program header size with the ELF header's [ElfFile.ph_entry_size] and [ElfFile.num_ph] members.
 *
 * http://www.sco.com/developers/gabi/latest/ch5.pheader.html#p_type
 * http://stackoverflow.com/questions/22612735/how-can-i-find-the-dynamic-libraries-required-by-an-elf-binary-in-c
 */
open class ElfSegment internal constructor(elfFile: ElfFile, parser: ElfParser, offset: Long) {

    /** Elf{32,64}_Phdr#p_type. Kind of segment this element describes. */
    @JvmField
    val type: Int // Elf32_Word/Elf64_Word - 4 bytes in both.
    /** Elf{32,64}_Phdr#p_offset. File offset at which the first byte of the segment resides. */
    @JvmField
    val offset: Long // Elf32_Off/Elf64_Off - 4 or 8 bytes.
    /** Elf{32,64}_Phdr#p_vaddr. Virtual address at which the first byte of the segment resides in memory. */
    @JvmField
    val virtual_address: Long // Elf32_Addr/Elf64_Addr - 4 or 8 bytes.
    /** Reserved for the physical address of the segment on systems where physical addressing is relevant. */
    @JvmField
    val physical_address: Long // Elf32_addr/Elf64_Addr - 4 or 8 bytes.

    /** Elf{32,64}_Phdr#p_filesz. File image size of segment in bytes, may be 0. */
    @JvmField
    val file_size: Long // Elf32_Word/Elf64_Xword -
    /** Elf{32,64}_Phdr#p_memsz. Memory image size of segment in bytes, may be 0. */
    @JvmField
    val mem_size: Long // Elf32_Word
    /**
     * Flags relevant to this segment. Values for flags are defined in ELFSectionHeader.
     */
    @JvmField
    val flags: Int // Elf32_Word
    @JvmField
    val alignment: Long // Elf32_Word

    private var ptInterpreter: MemoizedObject<String>? = null
    private var ptLoad: MemoizedObject<PtLoadData>? = null
    private var ehFrameHeader: MemoizedObject<GnuEhFrameHeader?>? = null
    private var dynamicStructure: MemoizedObject<ElfDynamicStructure>? = null
    private var arm_exidx: MemoizedObject<ArmExIdx>? = null

    init {
        parser.seek(offset)
        if (parser.elfFile.objectSize == ElfFile.CLASS_32) {
            // typedef struct {
            // Elf32_Word p_type;
            // Elf32_Off p_offset;
            // Elf32_Addr p_vaddr;
            // Elf32_Addr p_paddr;
            // Elf32_Word p_filesz;
            // Elf32_Word p_memsz;
            // Elf32_Word p_flags;
            // Elf32_Word p_align;
            // } Elf32_Phdr;
            type = parser.readInt()
            this.offset = parser.readInt().toLong()
            virtual_address = parser.readInt().toLong()
            physical_address = parser.readInt().toLong()
            file_size = parser.readInt().toLong()
            mem_size = parser.readInt().toLong()
            flags = parser.readInt()
            alignment = parser.readInt().toLong()
        } else {
            // typedef struct {
            // Elf64_Word p_type;
            // Elf64_Word p_flags;
            // Elf64_Off p_offset;
            // Elf64_Addr p_vaddr;
            // Elf64_Addr p_paddr;
            // Elf64_Xword p_filesz;
            // Elf64_Xword p_memsz;
            // Elf64_Xword p_align;
            // } Elf64_Phdr;
            type = parser.readInt()
            flags = parser.readInt()
            this.offset = parser.readLong()
            virtual_address = parser.readLong()
            physical_address = parser.readLong()
            file_size = parser.readLong()
            mem_size = parser.readLong()
            alignment = parser.readLong()
        }

        when (type) {
            PT_INTERP -> {
                ptInterpreter = object : MemoizedObject<String>() {
                    @Throws(ElfException::class)
                    override fun computeValue(): String {
                        parser.seek(this@ElfSegment.offset)
                        val buffer = StringBuilder()
                        var b: Int
                        while ((parser.readUnsignedByte().toInt().also { b = it }) != 0)
                            buffer.append(b.toChar())
                        return buffer.toString()
                    }
                }
            }
            PT_LOAD -> {
                ptLoad = object : MemoizedObject<PtLoadData>() {
                    @Throws(ElfException::class)
                    override fun computeValue(): PtLoadData {
                        parser.seek(this@ElfSegment.offset)
                        val buffer = parser.readBuffer(file_size.toInt())
                        return PtLoadData(buffer, file_size)
                    }
                }
            }
            PT_DYNAMIC -> {
                dynamicStructure = object : MemoizedObject<ElfDynamicStructure>() {
                    @Throws(ElfException::class, IOException::class)
                    override fun computeValue(): ElfDynamicStructure {
                        return ElfDynamicStructure(elfFile, parser, elfFile.virtualMemoryAddrToFileOffset(virtual_address), mem_size.toInt())
                    }
                }
            }
            PT_GNU_EH_FRAME -> {
                ehFrameHeader = object : MemoizedObject<GnuEhFrameHeader?>() {
                    @Throws(ElfException::class, IOException::class)
                    override fun computeValue(): GnuEhFrameHeader? {
                        if (mem_size <= 0 || virtual_address <= 0) {
                            return null
                        }
                        return GnuEhFrameHeader(parser, elfFile.virtualMemoryAddrToFileOffset(virtual_address), mem_size.toInt(), virtual_address)
                    }
                }
            }
            PT_ARM_EXIDX -> {
                arm_exidx = object : MemoizedObject<ArmExIdx>() {
                    @Throws(ElfException::class)
                    override fun computeValue(): ArmExIdx {
                        parser.seek(this@ElfSegment.offset)
                        val buffer = parser.readBuffer(file_size.toInt())
                        return ArmExIdx(this@ElfSegment.virtual_address, buffer)
                    }
                }
            }
        }
    }

    override fun toString(): String {
        val typeString: String
        when (type) {
            PT_NULL -> typeString = "PT_NULL"
            PT_LOAD -> typeString = "PT_LOAD"
            PT_DYNAMIC -> typeString = "PT_DYNAMIC"
            PT_INTERP -> typeString = "PT_INTERP"
            PT_NOTE -> typeString = "PT_NOTE"
            PT_SHLIB -> typeString = "PT_SHLIB"
            PT_PHDR -> typeString = "PT_PHDR"
            PT_GNU_EH_FRAME -> typeString = "PT_GNU_EH_FRAME"
            PT_ARM_EXIDX -> typeString = "PT_ARM_EXIDX"
            else -> typeString = "0x" + java.lang.Long.toHexString(type.toLong())
        }

        val pFlagsString = StringBuilder()
        if ((flags and PF_R) != 0) {
            pFlagsString.append("R")
        }
        if ((flags and PF_W) != 0) {
            if (pFlagsString.length > 0) {
                pFlagsString.append("|")
            }
            pFlagsString.append("W")
        }
        if ((flags and PF_X) != 0) {
            if (pFlagsString.length > 0) {
                pFlagsString.append("|")
            }
            pFlagsString.append("E")
        }
        if (pFlagsString.length == 0) {
            pFlagsString.append("0x").append(java.lang.Long.toHexString(flags.toLong()))
        }

        return ("ElfProgramHeader[p_type=" + typeString + ", p_filesz=0x" + java.lang.Long.toHexString(file_size) + ", p_memsz=0x" + java.lang.Long.toHexString(mem_size) + ", p_flags=" + pFlagsString + ", p_align="
                + alignment + ", range=[0x" + java.lang.Long.toHexString(virtual_address) + "-0x" + java.lang.Long.toHexString(virtual_address + mem_size) + "]]")
    }

    /** Only for [PT_INTERP] headers. */
    @Throws(IOException::class)
    open fun getInterpreter(): String? {
        return if (ptInterpreter == null) null else ptInterpreter!!.getValue()
    }

    @Throws(IOException::class)
    fun getPtLoadData(): PtLoadData? {
        return if (ptLoad == null) null else ptLoad!!.getValue()
    }

    @Throws(IOException::class)
    fun getDynamicStructure(): ElfDynamicStructure? {
        return if (dynamicStructure == null) null else dynamicStructure!!.getValue()
    }

    fun getEhFrameHeader(): MemoizedObject<GnuEhFrameHeader?>? {
        return ehFrameHeader
    }

    fun getARMExIdxData(): MemoizedObject<ArmExIdx>? {
        return arm_exidx
    }

    companion object {
        /** Type defining that the array element is unused. Other member values are undefined. */
        const val PT_NULL = 0
        /** Type defining that the array element specifies a loadable segment. */
        const val PT_LOAD = 1
        /** The array element specifies dynamic linking information. */
        const val PT_DYNAMIC = 2
        /**
         * The array element specifies the location and size of a null-terminated path name to invoke as an interpreter.
         * Meaningful only for executable files (though it may occur for shared objects); it may not occur more than once in
         * a file. If it is present, it must precede any loadable segment entry.
         */
        const val PT_INTERP = 3
        /** The array element specifies the location and size of auxiliary information. */
        const val PT_NOTE = 4
        /** This segment type is reserved but has unspecified semantics. */
        const val PT_SHLIB = 5
        /**
         * The array element, if present, specifies the location and size of the program header table itself, both in the
         * file and in the memory image of the program. This segment type may not occur more than once in a file.
         */
        const val PT_PHDR = 6
        /** The array element specifies the Thread-Local Storage template. */
        const val PT_TLS = 7

        /** Lower bound of the range reserved for operating system-specific semantics. */
        const val PT_LOOS = 0x60000000
        /** EH frame segment */
        const val PT_GNU_EH_FRAME = 0x6474e550
        /** Upper bound of the range reserved for operating system-specific semantics. */
        const val PT_HIOS = 0x6fffffff
        /** Lower bound of the range reserved for processor-specific semantics. */
        const val PT_LOPROC = 0x70000000
        /** .ARM.exidx segment */
        const val PT_ARM_EXIDX = 0x70000001
        /** Upper bound of the range reserved for processor-specific semantics. */
        const val PT_HIPROC = 0x7fffffff

        const val PF_R = 4
        const val PF_W = 2
        const val PF_X = 1
    }
}
