package com.vortexdbg.ios.dsc

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Path B (Vortex-iOS) — leitor do dyld_shared_cache, em Kotlin com tipos UNSIGNED.
 *
 * PRIMEIRO TIJOLO do subsistema que mapeia o cache INTEIRO na memória do emulador (em vez
 * de extrair dylibs individuais, que o R1 provou inviável: os segmentos extraídos mantêm os
 * vmaddrs do cache, não-alinhados).
 *
 * Parseia o header (magic) e as MAPPINGS de um arquivo de cache (principal ou sub-cache
 * `.NN`): cada mapping diz {address, size, fileOffset, prot} — o que será mmap'd no vmaddr
 * do cache. O cache moderno é fatiado em vários arquivos; cada um é um cache completo com
 * header+mappings próprios, então basta iterar os arquivos.
 *
 * Layout (estável): dyld_cache_header.magic[16]@0, mappingOffset(u32)@16, mappingCount(u32)@20;
 * dyld_cache_mapping_info (32B): address(u64)@0, size@8, fileOffset@16, maxProt(u32)@24, initProt@28.
 */
class DyldSharedCache(val file: File) {

    data class Mapping(
        val address: ULong,
        val size: ULong,
        val fileOffset: ULong,
        val maxProt: UInt,
        val initProt: UInt,
    ) {
        override fun toString(): String =
            "addr=0x%x size=0x%x (%dKB) fileOff=0x%x prot=%d/%d".format(
                address.toLong(), size.toLong(), (size / 1024u).toLong(),
                fileOffset.toLong(), initProt.toInt(), maxProt.toInt(),
            )
    }

    val magic: String
    val mappings: List<Mapping>

    init {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(0x10000)
            val n = raf.read(head)
            require(n >= 0x100) { "cache muito pequeno: $file" }
            val bb = ByteBuffer.wrap(head, 0, n).order(ByteOrder.LITTLE_ENDIAN)

            val m = ByteArray(16)
            bb.position(0)
            bb.get(m)
            magic = m.takeWhile { it.toInt() != 0 }.toByteArray().toString(Charsets.US_ASCII)
            require(magic.startsWith("dyld_v1")) { "não é dyld_shared_cache (magic='$magic'): $file" }

            val mappingOffset = bb.getInt(16)
            val mappingCount = bb.getInt(20)
            mappings = (0 until mappingCount).map { i ->
                val off = mappingOffset + i * 32
                Mapping(
                    address = bb.getLong(off).toULong(),
                    size = bb.getLong(off + 8).toULong(),
                    fileOffset = bb.getLong(off + 16).toULong(),
                    maxProt = bb.getInt(off + 24).toUInt(),
                    initProt = bb.getInt(off + 28).toUInt(),
                )
            }
        }
    }

    /** Uma imagem (dylib) dentro do cache: endereço de carga (__TEXT) + path. */
    data class Image(val loadAddress: ULong, val path: String)

    /**
     * Path B [4] — lista as imagens (dylibs) do cache via o array imagesText do header.
     * dyld_cache_header: imagesTextOffset(u64)@0x88, imagesTextCount(u64)@0x90.
     * dyld_cache_image_text_info (32B): uuid[16], loadAddress(u64)@16, textSize(u32)@24, pathOffset(u32)@28.
     * Só o cache PRINCIPAL tem imagens (sub-caches retornam vazio).
     */
    fun images(): List<Image> {
        RandomAccessFile(file, "r").use { raf ->
            val hdr = ByteArray(0x100)
            raf.seek(0)
            raf.readFully(hdr)
            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            val imagesTextOffset = bb.getLong(0x88)
            val imagesTextCount = bb.getLong(0x90)
            if (imagesTextCount <= 0 || imagesTextCount > 1_000_000) {
                return emptyList()
            }
            val arr = ByteArray((imagesTextCount * 32).toInt())
            raf.seek(imagesTextOffset)
            raf.readFully(arr)
            val ab = ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN)
            return (0 until imagesTextCount.toInt()).map { i ->
                val base = i * 32
                val loadAddress = ab.getLong(base + 16).toULong()
                val pathOffset = ab.getInt(base + 28).toLong() and 0xffffffffL
                Image(loadAddress, readCString(raf, pathOffset))
            }
        }
    }

    private fun readCString(raf: RandomAccessFile, offset: Long): String {
        raf.seek(offset)
        val buf = ByteArray(512)
        val n = raf.read(buf)
        var z = 0
        while (z < n && buf[z].toInt() != 0) {
            z++
        }
        return String(buf, 0, z, Charsets.US_ASCII)
    }

    companion object {
        /** Os arquivos do cache: o principal + os sub-caches `.NN` (na mesma pasta). */
        fun cacheFiles(mainCache: File): List<File> {
            val base = mainCache.name
            val subs = mainCache.parentFile
                .listFiles { _, name ->
                    name.startsWith("$base.") && name.substringAfterLast('.').matches(Regex("\\d+"))
                }
                ?.sortedBy { it.name }
                ?: emptyList()
            return listOf(mainCache) + subs
        }
    }
}
