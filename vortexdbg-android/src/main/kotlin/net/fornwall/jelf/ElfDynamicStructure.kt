package net.fornwall.jelf

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.Collections

/**
 * [dynamic.html](http://www.sco.com/developers/gabi/latest/ch5.dynamic.html#dynamic_section)
 * "If an object file participates in dynamic linking, its program header table will have an element of type PT_DYNAMIC. This ``segment'' contains the .dynamic
 * section. A special symbol, _DYNAMIC, labels the section, which contains an array of the following structures."
 *
 * ```
 * typedef struct { Elf32_Sword d_tag; union { Elf32_Word d_val; Elf32_Addr d_ptr; } d_un; } Elf32_Dyn;
 * extern Elf32_Dyn _DYNAMIC[];
 *
 * typedef struct { Elf64_Sxword d_tag; union { Elf64_Xword d_val; Elf64_Addr d_ptr; } d_un; } Elf64_Dyn;
 * extern Elf64_Dyn _DYNAMIC[];
 * ```
 */
open class ElfDynamicStructure internal constructor(elfFile: ElfFile, parser: ElfParser, offset: Long, size: Int) {

    /** For the [DT_STRTAB]. Mandatory. */
    @JvmField
    var dt_strtab_offset: Long = 0
    /** For the [DT_STRSZ]. Mandatory. */
    private var dt_strtab_size = 0

    private var dtStringTable: MemoizedObject<ElfStringTable>? = null
    private val dtNeeded: IntArray
    @JvmField
    val soName: Int
    private val init: Int
    private var initArrayOffset: Long = 0
    private var preInitArrayOffset: Long = 0
    private var initArraySize = 0
    private var preInitArraySize = 0
    private var initArray: MemoizedObject<ElfInitArray>? = null
    private var preInitArray: MemoizedObject<ElfInitArray>? = null
    private var symbolStructure: MemoizedObject<ElfSymbolStructure>? = null

    private var symbolEntrySize = 0
    private var symbolOffset: Long = 0

    private var hashOffset: Long = 0
    private var gnuHashOffset: Long = 0

    private var relOffset: Long = 0
    private var relSize = 0
    private var relEntrySize = 0

    private var pltRelOffset: Long = 0
    private var pltRelSize = 0

    private var androidRelOffset: Long = 0
    private var androidRelAOffset: Long = 0
    private var androidRelSize = 0
    private var androidRelASize = 0

    private var rel: Array<MemoizedObject<ElfRelocation>>? = null
    private var pltRel: Array<MemoizedObject<ElfRelocation>>? = null
    private var androidRelocation: MemoizedObject<AndroidRelocation>? = null

    private var armExIdx: Long = 0

    init {
        parser.seek(offset)
        val numEntries = size / (if (parser.elfFile.objectSize == ElfFile.CLASS_32) 8 else 16)

        val dtNeededList: MutableList<Int> = ArrayList()
        // Except for the DT_NULL element at the end of the array, and the relative order of DT_NEEDED elements, entries
        // may appear in any order. So important to use lazy evaluation to only evaluating e.g. DT_STRTAB after the
        // necessary DT_STRSZ is read.
        var soName = -1
        var init = 0

        // Avoid divide-by-zero exceptions
        // rela struct 64bit: https://llvm.org/doxygen/structllvm_1_1ELF_1_1Elf64__Rela.html
        // 32bit: https://llvm.org/doxygen/structllvm_1_1ELF_1_1Elf32__Rela.html
        relEntrySize = if (elfFile.arch.toInt() == ElfFile.CLASS_64.toInt()) 24 else 12

        loop@ for (i in 0 until numEntries) {
            val d_tag = parser.readIntOrLong()
            val d_val_or_ptr = parser.readIntOrLong()
            when (d_tag.toInt()) {
                DT_NULL ->
                    // A DT_NULL element ends the array (may be following DT_NULL values, but no need to look at them).
                    break@loop
                DT_NEEDED -> dtNeededList.add(d_val_or_ptr.toInt())
                DT_STRTAB -> dt_strtab_offset = d_val_or_ptr
                DT_STRSZ -> {
                    if (d_val_or_ptr > Integer.MAX_VALUE) throw ElfException("Too large DT_STRSZ: $d_val_or_ptr")
                    dt_strtab_size = d_val_or_ptr.toInt()
                }
                DT_SONAME -> soName = d_val_or_ptr.toInt()
                DT_INIT -> init = d_val_or_ptr.toInt()
                DT_INIT_ARRAY -> initArrayOffset = d_val_or_ptr
                DT_INIT_ARRAYSZ -> initArraySize = d_val_or_ptr.toInt()
                DT_PREINIT_ARRAY -> preInitArrayOffset = d_val_or_ptr
                DT_PREINIT_ARRAYSZ -> preInitArraySize = d_val_or_ptr.toInt()
                DT_SYMENT -> symbolEntrySize = d_val_or_ptr.toInt()
                DT_SYMTAB -> symbolOffset = d_val_or_ptr
                DT_HASH -> hashOffset = d_val_or_ptr
                DT_GNU_HASH -> gnuHashOffset = d_val_or_ptr
                DT_RELA, DT_REL -> relOffset = d_val_or_ptr
                DT_RELASZ, DT_RELSZ -> relSize = d_val_or_ptr.toInt()
                DT_RELAENT, DT_RELENT -> relEntrySize = d_val_or_ptr.toInt()
                DT_PLTRELSZ -> pltRelSize = d_val_or_ptr.toInt()
                DT_JMPREL -> pltRelOffset = d_val_or_ptr
                DT_ANDROID_RELASZ -> androidRelASize = d_val_or_ptr.toInt()
                DT_ANDROID_RELSZ -> androidRelSize = d_val_or_ptr.toInt()
                DT_ANDROID_RELA -> androidRelAOffset = d_val_or_ptr
                DT_ANDROID_REL -> androidRelOffset = d_val_or_ptr
                SHT_ARM_EXIDX -> {
                    armExIdx = d_val_or_ptr
                    log.debug("armExIdx=0x{}", java.lang.Long.toHexString(armExIdx))
                }
                DT_VERSYM, DT_RELACOUNT, DT_RELCOUNT, DT_FLAGS_1, DT_VERDEF, DT_VERDEFNUM, DT_VERNEEDED, DT_VERNEEDNUM, DT_AUXILIARY -> {
                }
                else -> {
                    val androidTag = (d_tag and 0x60000000L) != 0L
                    if (androidTag) {
                        log.warn("Unsupported android tag: 0x" + java.lang.Long.toHexString(d_tag))
                    }
                }
            }
        }

        if (dt_strtab_size > 0) {
            dtStringTable = object : MemoizedObject<ElfStringTable>() {
                @Throws(ElfException::class, IOException::class)
                override fun computeValue(): ElfStringTable {
                    return ElfStringTable(parser, elfFile.virtualMemoryAddrToFileOffset(dt_strtab_offset), dt_strtab_size)
                }
            }
        }

        val hashTable: MemoizedObject<HashTable>?
        if (hashOffset > 0) {
            hashTable = object : MemoizedObject<HashTable>() {
                @Throws(ElfException::class, IOException::class)
                override fun computeValue(): HashTable {
                    return ElfHashTable(parser, elfFile.virtualMemoryAddrToFileOffset(hashOffset), -1)
                }
            }
        } else if (gnuHashOffset > 0) {
            hashTable = object : MemoizedObject<HashTable>() {
                @Throws(ElfException::class, IOException::class)
                override fun computeValue(): HashTable {
                    return ElfGnuHashTable(parser, elfFile.virtualMemoryAddrToFileOffset(gnuHashOffset))
                }
            }
        } else {
            hashTable = null
        }

        if (symbolOffset > 0) {
            symbolStructure = object : MemoizedObject<ElfSymbolStructure>() {
                @Throws(ElfException::class, IOException::class)
                override fun computeValue(): ElfSymbolStructure {
                    return ElfSymbolStructure(parser, elfFile.virtualMemoryAddrToFileOffset(symbolOffset), symbolEntrySize, dtStringTable!!, hashTable)
                }
            }
        }

        if (relOffset > 0) {
            val num_entries = relSize / relEntrySize
            rel = MemoizedObject.uncheckedArray(num_entries)
            val fileOffset = elfFile.virtualMemoryAddrToFileOffset(relOffset)
            for (i in 0 until num_entries) {
                val relocationOffset = fileOffset + (i.toLong() * relEntrySize)
                rel!![i] = object : MemoizedObject<ElfRelocation>() {
                    @Throws(IOException::class)
                    override fun computeValue(): ElfRelocation {
                        return ElfRelocation(parser, relocationOffset, relEntrySize.toLong(), symbolStructure!!.getValue())
                    }
                }
            }
        }

        if (pltRelOffset > 0) {
            val num_entries = pltRelSize / relEntrySize
            pltRel = MemoizedObject.uncheckedArray(num_entries)
            val fileOffset = elfFile.virtualMemoryAddrToFileOffset(pltRelOffset)
            for (i in 0 until num_entries) {
                val relocationOffset = fileOffset + (i.toLong() * relEntrySize)
                pltRel!![i] = object : MemoizedObject<ElfRelocation>() {
                    @Throws(IOException::class)
                    override fun computeValue(): ElfRelocation {
                        return ElfRelocation(parser, relocationOffset, relEntrySize.toLong(), symbolStructure!!.getValue())
                    }
                }
            }
        }

        if (androidRelOffset > 0) {
            assert(symbolStructure != null)
            androidRelocation = object : MemoizedObject<AndroidRelocation>() {
                @Throws(ElfException::class, IOException::class)
                override fun computeValue(): AndroidRelocation {
                    parser.seek(elfFile.virtualMemoryAddrToFileOffset(androidRelOffset))
                    val magic = ByteArray(4)
                    parser.read(magic)
                    if (androidRelSize >= 4 && "APS2" == String(magic)) {
                        val androidRelData = parser.readBuffer(androidRelSize - 4)
                        return AndroidRelocation(parser, symbolStructure!!.getValue(), androidRelData, false)
                    } else {
                        throw IllegalStateException("bad android relocation header.")
                    }
                }
            }
        } else if (androidRelAOffset > 0) {
            assert(symbolStructure != null)
            androidRelocation = object : MemoizedObject<AndroidRelocation>() {
                @Throws(ElfException::class, IOException::class)
                override fun computeValue(): AndroidRelocation {
                    parser.seek(elfFile.virtualMemoryAddrToFileOffset(androidRelAOffset))
                    val magic = ByteArray(4)
                    parser.read(magic)
                    if (androidRelASize >= 4 && "APS2" == String(magic)) {
                        val androidRelData = parser.readBuffer(androidRelASize - 4)
                        return AndroidRelocation(parser, symbolStructure!!.getValue(), androidRelData, true)
                    } else {
                        throw IllegalStateException("bad android relocation header.")
                    }
                }
            }
        }

        if (initArraySize > 0) {
            initArray = object : MemoizedObject<ElfInitArray>() {
                @Throws(ElfException::class, IOException::class)
                override fun computeValue(): ElfInitArray {
                    return ElfInitArray(parser, elfFile.virtualMemoryAddrToFileOffset(initArrayOffset), initArraySize)
                }
            }
        }

        if (preInitArraySize > 0) {
            preInitArray = object : MemoizedObject<ElfInitArray>() {
                @Throws(ElfException::class, IOException::class)
                override fun computeValue(): ElfInitArray {
                    return ElfInitArray(parser, elfFile.virtualMemoryAddrToFileOffset(preInitArrayOffset), preInitArraySize)
                }
            }
        }

        dtNeeded = IntArray(dtNeededList.size)
        var i = 0
        val len = dtNeeded.size
        while (i < len) {
            dtNeeded[i] = dtNeededList[i]
            i++
        }

        this.soName = soName
        this.init = init
    }

    @Throws(IOException::class)
    fun getSOName(fileName: String): String {
        val stringTable = dtStringTable!!.getValue()
        return if (soName == -1) fileName else stringTable.get(soName)
    }

    fun getInit(): Int {
        return init
    }

    @Throws(ElfException::class, IOException::class)
    fun getNeededLibraries(): List<String> {
        val result: MutableList<String> = ArrayList()
        val stringTable = dtStringTable!!.getValue()
        for (needed in dtNeeded) {
            result.add(stringTable.get(needed))
        }
        return result
    }

    fun getInitArrayOffset(): Long {
        return initArrayOffset
    }

    fun getPreInitArrayOffset(): Long {
        return preInitArrayOffset
    }

    fun getInitArraySize(): Int {
        return initArraySize
    }

    fun getPreInitArraySize(): Int {
        return preInitArraySize
    }

    @Throws(IOException::class)
    fun getInitArray(): ElfInitArray? {
        return if (initArray == null) null else initArray!!.getValue()
    }

    @Throws(IOException::class)
    fun getPreInitArray(): ElfInitArray? {
        return if (preInitArray == null) null else preInitArray!!.getValue()
    }

    @Throws(IOException::class)
    fun getSymbolStructure(): ElfSymbolStructure {
        return symbolStructure!!.getValue()
    }

    @Throws(IOException::class)
    fun getRelocations(): Collection<MemoizedObject<ElfRelocation>> {
        val list: MutableList<MemoizedObject<ElfRelocation>> = ArrayList()
        if (androidRelocation != null) {
            for (elfRelocationMemoizedObject in androidRelocation!!.getValue()) {
                list.add(elfRelocationMemoizedObject)
            }
        }
        if (rel != null) {
            Collections.addAll(list, *rel!!)
        }
        if (pltRel != null) {
            Collections.addAll(list, *pltRel!!)
        }
        return list
    }

    override fun toString(): String {
        return "ElfDynamicStructure[]"
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ElfDynamicStructure::class.java)

        private const val DT_NULL = 0
        private const val DT_NEEDED = 1
        private const val DT_PLTRELSZ = 2
        const val DT_PLTGOT = 3
        private const val DT_HASH = 4
        /** DT_STRTAB entry holds the address, not offset, of the dynamic string table. */
        private const val DT_STRTAB = 5
        private const val DT_SYMTAB = 6
        const val DT_RELA = 7
        const val DT_RELASZ = 8
        const val DT_RELAENT = 9
        /** The size in bytes of the [DT_STRTAB] string table. */
        private const val DT_STRSZ = 10
        private const val DT_SYMENT = 11
        private const val DT_INIT = 12
        const val DT_FINI = 13
        private const val DT_SONAME = 14
        const val DT_RPATH = 15
        private const val DT_REL = 17
        private const val DT_RELSZ = 18
        private const val DT_RELENT = 19
        private const val DT_JMPREL = 23
        private const val DT_INIT_ARRAY = 25
        private const val DT_INIT_ARRAYSZ = 27
        const val DT_RUNPATH = 29
        private const val DT_PREINIT_ARRAY = 32
        private const val DT_PREINIT_ARRAYSZ = 33
        private const val DT_VERSYM = 0x6ffffff0
        private const val DT_GNU_HASH = 0x6ffffef5
        private const val DT_RELACOUNT = 0x6ffffff9
        private const val DT_RELCOUNT = 0x6ffffffa
        private const val DT_FLAGS_1 = 0x6ffffffb
        private const val DT_VERDEF = 0x6ffffffc /* Address of version definition */
        private const val DT_VERDEFNUM = 0x6ffffffd /* Number of version definitions */
        private const val DT_VERNEEDED = 0x6ffffffe
        private const val DT_VERNEEDNUM = 0x6fffffff
        private const val DT_AUXILIARY = 0x7ffffffd

        private const val DT_ANDROID_REL = 0x6000000f
        private const val DT_ANDROID_RELSZ = 0x60000010
        private const val DT_ANDROID_RELA = 0x60000011
        private const val DT_ANDROID_RELASZ = 0x60000012
        private const val SHT_ARM_EXIDX = 0x70000001 /* Exception index table. */

        /** Some values of [DT_FLAGS_1]. */
        const val DF_1_NOW = 0x00000001 /* Set RTLD_NOW for this object. */
        const val DF_1_GLOBAL = 0x00000002 /* Set RTLD_GLOBAL for this object. */
        const val DF_1_GROUP = 0x00000004 /* Set RTLD_GROUP for this object. */
        const val DF_1_NODELETE = 0x00000008 /* Set RTLD_NODELETE for this object. */
    }
}
