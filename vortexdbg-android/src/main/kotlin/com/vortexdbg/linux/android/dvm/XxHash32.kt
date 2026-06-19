package com.vortexdbg.linux.android.dvm

import java.nio.charset.StandardCharsets

/**
 * xxHash32 — pure Java 1.8 implementation.
 * Ported from the original C spec by Yann Collet.
 * <a href="https://github.com/Cyan4973/xxHash">xxHash</a>
 * <br />
 * Thread-safe: all methods are static, no mutable state.
 */
internal object XxHash32 {

    private val PRIME1 = 0x9E3779B1.toInt()
    private val PRIME2 = 0x85EBCA77.toInt()
    private val PRIME3 = 0xC2B2AE3D.toInt()
    private const val PRIME4 = 0x27D4EB2F
    private const val PRIME5 = 0x165667B1

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /** Hash a String (UTF-8 encoded). */
    @JvmStatic
    fun hash(input: String): Int {
        return hash(input, 0)
    }

    /** Hash a String with a custom seed; only equal seeds yield comparable hashes across processes. */
    @JvmStatic
    fun hash(input: String, seed: Int): Int {
        val bytes = input.toByteArray(StandardCharsets.UTF_8)
        return hash(bytes, 0, bytes.size, seed)
    }

    /** Hash a raw byte array. */
    @JvmStatic
    fun hash(data: ByteArray, offset: Int, len: Int, seed: Int): Int {
        var h32: Int
        var i = offset
        val end = offset + len

        if (len >= 16) {
            // 4-lane accumulator init
            var v1 = seed + PRIME1 + PRIME2
            var v2 = seed + PRIME2
            var v3 = seed
            var v4 = seed - PRIME1

            val limit = end - 16
            do {
                v1 = round(v1, getInt(data, i)); i += 4
                v2 = round(v2, getInt(data, i)); i += 4
                v3 = round(v3, getInt(data, i)); i += 4
                v4 = round(v4, getInt(data, i)); i += 4
            } while (i <= limit)

            h32 = (Integer.rotateLeft(v1, 1)
                    + Integer.rotateLeft(v2, 7)
                    + Integer.rotateLeft(v3, 12)
                    + Integer.rotateLeft(v4, 18))
        } else {
            h32 = seed + PRIME5
        }

        h32 += len

        // consume remaining bytes in 4-byte chunks
        while (i + 4 <= end) {
            h32 += getInt(data, i) * PRIME3
            h32 = Integer.rotateLeft(h32, 17) * PRIME4
            i += 4
        }

        // consume leftover bytes one at a time
        while (i < end) {
            h32 += (data[i].toInt() and 0xFF) * PRIME5
            h32 = Integer.rotateLeft(h32, 11) * PRIME1
            i++
        }

        return fmix(h32)
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    private fun round(acc: Int, input: Int): Int {
        var a = acc
        a += input * PRIME2
        a = Integer.rotateLeft(a, 13)
        a *= PRIME1
        return a
    }

    /** Final avalanche mix — ensures every input bit affects every output bit. */
    private fun fmix(h: Int): Int {
        var x = h
        x = x xor (x ushr 15)
        x *= PRIME2
        x = x xor (x ushr 13)
        x *= PRIME3
        x = x xor (x ushr 16)
        return x
    }

    /** Read 4 bytes as little-endian int (xxHash spec). */
    private fun getInt(data: ByteArray, i: Int): Int {
        return ((data[i].toInt() and 0xFF)
                or ((data[i + 1].toInt() and 0xFF) shl 8)
                or ((data[i + 2].toInt() and 0xFF) shl 16)
                or ((data[i + 3].toInt() and 0xFF) shl 24))
    }
}
