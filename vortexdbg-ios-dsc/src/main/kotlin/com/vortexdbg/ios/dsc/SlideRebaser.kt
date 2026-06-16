package com.vortexdbg.ios.dsc

import com.vortexdbg.Emulator
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Path B [3] — aplica a slide info V5 numa região DATA JÁ MAPEADA na memória emulada. Os
 * ponteiros no DATA do cache estão em formato ENCADEADO (dyld_cache_slide_pointer5), não como
 * VAs reais; é preciso percorrer as cadeias (page_starts + campo `next`) e reescrever cada
 * ponteiro com o VA de runtime — mesmo com slide=0, pois o formato on-disk é packed.
 *
 * dyld_cache_slide_pointer5 (união, 64 bits, LSB→MSB):
 *   runtimeOffset:34, high8:8, unused:10, next:11, auth:1
 * VA = value_add + runtimeOffset + slide; se !auth, OR (high8 << 56). `next` é stride de 8 bytes
 * até o próximo ponteiro (0 encerra a cadeia). PAC (auth) é ignorado na emulação (sem assinatura).
 */
object SlideRebaser {

    private const val NO_REBASE = 0xFFFF
    private const val MASK34 = 0x3FFFFFFFFL  // (1<<34)-1

    /**
     * Rebaseia UMA página (já mapeada) cujo 1º ponteiro encadeado está em [pageAddr]+[pageStart].
     * Para o mapeamento lazy: ao faltar uma página de uma região com slide, rebaseia só ela.
     */
    fun rebasePage(emulator: Emulator<*>, pageAddr: Long, valueAdd: Long, pageStart: Int, slide: Long = 0): Int {
        if (pageStart == NO_REBASE) return 0
        val backend = emulator.backend
        var loc = pageAddr + pageStart
        var count = 0
        while (true) {
            val raw = ByteBuffer.wrap(backend.mem_read(loc, 8)).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
            val runtimeOffset = raw and MASK34
            val next = ((raw ushr 52) and 0x7FF).toInt()
            val auth = (raw ushr 63) and 1L
            val newVal = if (auth == 1L) valueAdd + runtimeOffset + slide
                else (valueAdd + runtimeOffset + slide) or (((raw ushr 34) and 0xFF) shl 56)
            backend.mem_write(loc, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(newVal).array())
            count++
            if (next == 0) break
            loc += next.toLong() * 8
        }
        return count
    }

    /** Reescreve os ponteiros encadeados da região [mp] usando [si]. Retorna nº de ponteiros. */
    fun rebase(
        emulator: Emulator<*>,
        mp: DyldSharedCache.MappingSlide,
        si: DyldSharedCache.SlideInfo5,
        slide: Long = 0,
    ): Int {
        val backend = emulator.backend
        val pageSize = si.pageSize.toLong()
        var count = 0
        for (page in si.pageStarts.indices) {
            val start = si.pageStarts[page]
            if (start == NO_REBASE) continue
            var loc = mp.address.toLong() + page * pageSize + start
            while (true) {
                val raw = ByteBuffer.wrap(backend.mem_read(loc, 8)).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
                val runtimeOffset = raw and MASK34
                val next = ((raw ushr 52) and 0x7FF).toInt()
                val auth = (raw ushr 63) and 1L
                val newVal = if (auth == 1L) {
                    si.valueAdd.toLong() + runtimeOffset + slide
                } else {
                    val high8 = (raw ushr 34) and 0xFF
                    (si.valueAdd.toLong() + runtimeOffset + slide) or (high8 shl 56)
                }
                backend.mem_write(loc, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(newVal).array())
                count++
                if (next == 0) break
                loc += next.toLong() * 8
            }
        }
        return count
    }
}
