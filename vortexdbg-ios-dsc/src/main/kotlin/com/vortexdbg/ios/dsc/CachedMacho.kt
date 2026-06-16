package com.vortexdbg.ios.dsc

import com.vortexdbg.Emulator
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Path B — leitura do Mach-O de uma dylib JÁ MAPEADA no cache (memória emulada), a partir do
 * seu loadAddress. Parseia os load commands (segmentos + ponteiros de linkedit) lendo direto
 * da memória do emulador.
 *
 * Mach-O 64: magic@0, ... ncmds(u32)@16, sizeofcmds(u32)@20; load commands a partir de +0x20.
 * LC_SEGMENT_64 (0x19): cmd@0, cmdsize@4, segname[16]@8, vmaddr@24, vmsize@32, fileoff@40.
 * LC_DYLD_EXPORTS_TRIE (0x80000033, linkedit_data_command): cmd@0, cmdsize@4, dataoff@8, datasize@12.
 */
object CachedMacho {

    private const val LC_SEGMENT_64 = 0x19
    private const val LC_DYLD_EXPORTS_TRIE = 0x80000033.toInt()

    data class Segment(val name: String, val vmaddr: ULong, val vmsize: ULong, val fileOffset: ULong) {
        override fun toString() = "%-14s vmaddr=0x%x vmsize=0x%x".format(name, vmaddr.toLong(), vmsize.toLong())
    }

    /** Info parseada do Mach-O: segmentos + localização (file offset) da export trie. */
    data class MachoInfo(
        val segments: List<Segment>,
        val exportsTrieOffset: ULong,
        val exportsTrieSize: ULong,
    ) {
        fun segment(name: String): Segment? = segments.firstOrNull { it.name == name }
    }

    /** Parseia header + load commands da dylib mapeada em [loadAddress]. */
    fun parse(emulator: Emulator<*>, loadAddress: ULong): MachoInfo {
        val backend = emulator.backend
        val base = loadAddress.toLong()
        val header = ByteBuffer.wrap(backend.mem_read(base, 0x20)).order(ByteOrder.LITTLE_ENDIAN)
        val ncmds = header.getInt(16)
        val sizeofcmds = header.getInt(20)
        val cb = ByteBuffer.wrap(backend.mem_read(base + 0x20, sizeofcmds.toLong())).order(ByteOrder.LITTLE_ENDIAN)

        val segs = ArrayList<Segment>()
        var trieOff = 0uL
        var trieSize = 0uL
        var off = 0
        repeat(ncmds) {
            val cmd = cb.getInt(off)
            val cmdsize = cb.getInt(off + 4)
            when (cmd) {
                LC_SEGMENT_64 -> {
                    val name = ByteArray(16).also { cb.position(off + 8); cb.get(it) }
                        .let { b -> String(b, 0, b.indexOf(0).let { if (it < 0) 16 else it }, Charsets.US_ASCII) }
                    segs.add(Segment(name, cb.getLong(off + 24).toULong(), cb.getLong(off + 32).toULong(),
                        cb.getLong(off + 40).toULong()))
                }
                LC_DYLD_EXPORTS_TRIE -> {
                    trieOff = (cb.getInt(off + 8).toLong() and 0xffffffffL).toULong()
                    trieSize = (cb.getInt(off + 12).toLong() and 0xffffffffL).toULong()
                }
            }
            off += cmdsize
        }
        return MachoInfo(segs, trieOff, trieSize)
    }

    /** Lista só os segmentos (LC_SEGMENT_64) da dylib mapeada em [loadAddress]. */
    fun segments(emulator: Emulator<*>, loadAddress: ULong): List<Segment> = parse(emulator, loadAddress).segments
}
