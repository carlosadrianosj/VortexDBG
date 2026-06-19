package com.vortexdbg.linux.android.dvm

import java.nio.charset.StandardCharsets

enum class Hasher : HashFunction {

    Default {
        override fun hash(className: String): Int {
            return className.hashCode()
        }
    },

    FNV_1a {
        override fun hash(className: String): Int {
            var h = 0x811c9dc5.toInt()  // FNV offset basis
            for (i in 0 until className.length) {
                h = h xor className[i].code
                h *= 0x01000193  // FNV prime
            }
            return h
        }
    },

    MurmurHash3 {
        override fun hash(className: String): Int {
            val data = className.toByteArray(StandardCharsets.UTF_8)
            val len = data.size
            var h = 0x9747b28c.toInt()
            var i = 0

            while (i + 4 <= len) {
                var k = ((data[i].toInt() and 0xFF)
                        or ((data[i + 1].toInt() and 0xFF) shl 8)
                        or ((data[i + 2].toInt() and 0xFF) shl 16)
                        or ((data[i + 3].toInt() and 0xFF) shl 24))
                k *= 0xcc9e2d51.toInt()
                k = Integer.rotateLeft(k, 15)
                k *= 0x1b873593
                h = h xor k
                h = Integer.rotateLeft(h, 13)
                h = h * 5 + 0xe6546b64.toInt()
                i += 4
            }

            // tail bytes
            var tail = 0
            when (len and 3) {
                3 -> {
                    tail = tail xor ((data[i + 2].toInt() and 0xFF) shl 16) // fall through
                    tail = tail xor ((data[i + 1].toInt() and 0xFF) shl 8) // fall through
                    tail = tail xor (data[i].toInt() and 0xFF)
                    tail *= 0xcc9e2d51.toInt()
                    tail = Integer.rotateLeft(tail, 15)
                    tail *= 0x1b873593
                    h = h xor tail
                }
                2 -> {
                    tail = tail xor ((data[i + 1].toInt() and 0xFF) shl 8) // fall through
                    tail = tail xor (data[i].toInt() and 0xFF)
                    tail *= 0xcc9e2d51.toInt()
                    tail = Integer.rotateLeft(tail, 15)
                    tail *= 0x1b873593
                    h = h xor tail
                }
                1 -> {
                    tail = tail xor (data[i].toInt() and 0xFF)
                    tail *= 0xcc9e2d51.toInt()
                    tail = Integer.rotateLeft(tail, 15)
                    tail *= 0x1b873593
                    h = h xor tail
                }
            }

            // final mix (fmix)
            h = h xor len
            h = h xor (h ushr 16)
            h *= 0x85ebca6b.toInt()
            h = h xor (h ushr 13)
            h *= 0xc2b2ae35.toInt()
            h = h xor (h ushr 16)
            return h
        }
    },

    XxHash {
        override fun hash(className: String): Int {
            return XxHash32.hash(className)
        }
    };

}
