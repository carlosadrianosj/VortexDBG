package net.fornwall.jelf

import java.nio.ByteBuffer

class AndroidRelocation internal constructor(
    private val parser: ElfParser,
    private val symtab: SymbolLocator,
    private val androidRelData: ByteBuffer,
    private val rela: Boolean
) : Iterable<MemoizedObject<ElfRelocation>> {

    override fun iterator(): Iterator<MemoizedObject<ElfRelocation>> {
        return AndroidRelocationIterator(parser.elfFile.objectSize.toInt(), symtab, androidRelData, rela)
    }
}
