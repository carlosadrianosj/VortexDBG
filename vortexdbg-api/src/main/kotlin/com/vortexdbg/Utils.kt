package com.vortexdbg

import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class Utils {

    companion object {

        private val log: Logger = LoggerFactory.getLogger(Utils::class.java)

        /** Returns val represented by the specified number of hex digits.  */
        private fun digits(`val`: Long, digits: Int): String {
            val hi = 1L shl (digits * 4)
            return java.lang.Long.toHexString(hi or (`val` and (hi - 1))).substring(1)
        }

        @JvmStatic
        fun toUUID(data: ByteArray?): String? {
            if (data == null) {
                return null
            }

            var msb: Long = 0
            var lsb: Long = 0
            assert(data.size == 16) { "data must be 16 bytes in length" }
            for (i in 0..7) {
                msb = (msb shl 8) or (data[i].toLong() and 0xffL)
            }
            for (i in 8..15) {
                lsb = (lsb shl 8) or (data[i].toLong() and 0xffL)
            }
            val mostSigBits = msb
            val leastSigBits = lsb

            return (digits(mostSigBits shr 32, 8) + "-" +
                    digits(mostSigBits shr 16, 4) + "-" +
                    digits(mostSigBits, 4) + "-" +
                    digits(leastSigBits shr 48, 4) + "-" +
                    digits(leastSigBits, 12)).uppercase()
        }

        /**
         * Reads an signed integer from `buffer`.
         */
        @JvmStatic
        fun readSignedLeb128(buffer: ByteBuffer, size: Int): Long {
            var shift = 0
            var value: Long = 0
            var b: Long
            do {
                b = buffer.get().toLong() and 0xffL
                value = value or ((b and 0x7fL) shl shift)
                shift += 7
            } while ((b and 0x80L) != 0L)

            if (shift < size && ((b and 0x40L) != 0L)) {
                value = value or -(1L shl shift)
            }

            return value
        }

        @JvmStatic
        fun readULEB128(buffer: ByteBuffer): BigInteger {
            var result = BigInteger.ZERO
            var shift = 0
            while (true) {
                val b = buffer.get()
                result = result.or(BigInteger.valueOf((b.toInt() and 0x7f).toLong()).shiftLeft(shift))
                if ((b.toInt() and 0x80) == 0) {
                    break
                }
                shift += 7
            }
            return result
        }

        @JvmStatic
        @Throws(IOException::class)
        fun mapBuffer(file: File): ByteBuffer {
            FileInputStream(file).use { inputStream ->
                inputStream.channel.use { channel ->
                    return channel.map(FileChannel.MapMode.READ_ONLY, 0L, file.length())
                }
            }
        }

        @JvmStatic
        fun readFile(randomAccessFile: RandomAccessFile, buffer: Pointer, _count: Int): Int {
            try {
                var count = _count
                val remaining = randomAccessFile.length() - randomAccessFile.filePointer
                if (count > remaining) {
                    count = remaining.toInt()

                    /*
                     * lseek() allows the file offset to be set beyond the end of the file
                     *        (but this does not change the size of the file).  If data is later
                     *        written at this point, subsequent reads of the data in the gap (a
                     *        "hole") return null bytes ('\0') until data is actually written into
                     *        the gap.
                     */
                    if (count < 0) {
                        log.warn("read path={}, fp={}, _count={}, length={}, buffer={}", randomAccessFile, randomAccessFile.filePointer, _count, randomAccessFile.length(), buffer)
                        return 0
                    }
                }

                var total = 0
                val buf = ByteArray(Math.min(0x1000, count))
                var pointer = buffer
                while (total < count) {
                    val read = randomAccessFile.read(buf, 0, Math.min(buf.size, count - total))
                    if (read <= 0) {
                        if (log.isDebugEnabled) {
                            log.debug("read path={}, fp={}, read={}, length={}, buffer={}", randomAccessFile, randomAccessFile.filePointer, read, randomAccessFile.length(), buffer)
                        }
                        return total
                    }

                    if (randomAccessFile.filePointer > randomAccessFile.length()) {
                        throw IllegalStateException("fp=" + randomAccessFile.filePointer + ", length=" + randomAccessFile.length())
                    }

                    if (read > buf.size) {
                        throw IllegalStateException("count=" + buf.size + ", read=" + read)
                    }
                    if (log.isDebugEnabled) {
                        Inspector.inspect(buf, "read path=" + randomAccessFile + ", fp=" + randomAccessFile.filePointer + ", read=" + read + ", length=" + randomAccessFile.length() + ", buffer=" + buffer)
                    }
                    pointer.write(0L, buf, 0, read)
                    total += read
                    pointer = pointer.share(read.toLong())
                }
                return total
            } catch (e: IOException) {
                throw IllegalStateException()
            }
        }

        @JvmStatic
        fun getClassLocation(clazz: Class<*>): File {
            return File(clazz.protectionDomain.codeSource.location.path)
        }

        @JvmStatic
        fun parseNumber(str: String): Long {
            return if (str.startsWith("0x")) {
                java.lang.Long.parseLong(str.substring(2).trim(), 16)
            } else {
                java.lang.Long.parseLong(str)
            }
        }

        @JvmStatic
        fun decodeVectorRegister(data: ByteArray): String {
            if (data.size != 16) {
                throw IllegalStateException("data.length=" + data.size)
            }
            val buffer = ByteBuffer.allocate(16)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(data)
            buffer.flip()
            var twoDouble = false
            for (i in 8..15) {
                if (data[i].toInt() != 0) {
                    twoDouble = true
                    break
                }
            }
            if (twoDouble) {
                return String.format("(%s, %s)", buffer.double, buffer.double)
            }

            var isDouble = false
            for (i in 4..7) {
                if (data[i].toInt() != 0) {
                    isDouble = true
                    break
                }
            }
            return String.format("(%s)", if (isDouble) buffer.double else buffer.float)
        }

        @JvmStatic
        fun indexOfNullTerminator(data: ByteArray): Int {
            for (i in data.indices) {
                if (data[i].toInt() == 0) {
                    return i
                }
            }
            return data.size
        }

    }

}
