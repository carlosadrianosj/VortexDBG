package com.vortexdbg.ios.dsc

import com.vortexdbg.Emulator

/**
 * Path B [shim] — heap emulado simples (bump allocator) numa região RW própria do espaço do
 * emulador. Substitui o malloc real do cache (que precisaria do bootstrap do dyld4 pra
 * inicializar). free é no-op; realloc/calloc usam o tamanho rastreado. Suficiente pra rodar
 * código do cache que aloca (ex.: _strdup, e depois o objc).
 */
class HostHeap(private val emulator: Emulator<*>, val base: Long = 0x4A000000L, val size: Long = 0x8000000L) {

    private var next = base
    private val end = base + size
    private val sizes = HashMap<Long, Long>()

    init {
        emulator.backend.mem_map(base, size, 3) // RW
    }

    /** Aloca [n] bytes (alinhado a 16), devolve o ponteiro emulado. */
    fun alloc(n: Long): Long {
        val sz = maxOf(16L, (n + 15) and 0xFL.inv())
        val p = next
        check(p + sz <= end) { "host heap esgotado (pediu %d, base=0x%x)".format(n, base) }
        next += sz
        sizes[p] = n
        return p
    }

    fun free(@Suppress("UNUSED_PARAMETER") ptr: Long) { /* no-op */ }

    fun sizeOf(ptr: Long): Long = sizes[ptr] ?: 0L

    /** Aloca e zera [n] bytes. */
    fun allocZeroed(n: Long): Long {
        val p = alloc(n)
        if (n > 0) emulator.backend.mem_write(p, ByteArray(n.toInt()))
        return p
    }
}
