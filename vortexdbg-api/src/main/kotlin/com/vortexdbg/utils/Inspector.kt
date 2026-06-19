/*
 * Filename: Inspector.java
 * Create date: 2009-7-5
 */
package com.vortexdbg.utils

import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date

/**
 *
 * @author vortexdbg
 *
 */
@Suppress("unused")
open class Inspector {

    /**
     * Filter hook deciding whether the given data should be inspected.
     *
     * @return `true` to accept (inspect) the data
     */
    open fun accept(data: ByteArray, label: String): Boolean {
        return true
    }

    open fun acceptObject(obj: Any): Boolean {
        return true
    }

    /**
     * Detects the type of the given data.
     *
     * @return the detected type, or `null` if nothing was detected
     */
    protected open fun detectedType(data: ByteArray, send: Boolean): Int? {
        return null
    }

    companion object {

        @JvmStatic
        fun inspectMapData(label: String, data: ByteArray?, mode: Int) {
            if (data == null)
                return

            val buffer = StringBuilder()
            buffer.append("\n>-----------------------------------------------------------------------------<\n")

            buffer.append(SimpleDateFormat("[HH:mm:ss SSS]").format(Date()))

            buffer.append(label)

            buffer.append("\nsize: ").append(data.size).append('\n')

            var i = 0
            while (i < data.size) {
                val di = data[i].toInt() and 0xFF
                if (di != 0) {
                    val hex = Integer.toString(di, 16).toUpperCase()
                    if (hex.length < 2) {
                        buffer.append('0')
                    }
                    buffer.append(hex)
                } else {
                    buffer.append("  ")
                }

                buffer.append(' ')

                if ((i + 1) % mode == 0) {
                    /*buffer.append("   ");
                    for(int k = i - 15; k < i+1; k++) {
                        buffer.append(toChar(data[k]));
                    }*/
                    buffer.append('\n')
                }
                i++
            }

            val redex = mode - i % mode
            var k: Byte = 0
            while (k.toInt() < redex && redex < mode) {
                buffer.append("  ")
                buffer.append(' ')
                k = (k + 1).toByte()
            }
            val count = i % mode
            val start = i - count
            if (start < i) {
                buffer.append("   ")
            }
            var kk = start
            while (kk < i) {
                buffer.append(toChar(data[kk]))
                kk++
            }

            if (redex < mode) {
                buffer.append('\n')
            }
            buffer.append("^-----------------------------------------------------------------------------^")

            println(buffer)
        }

        @JvmStatic
        @Throws(IOException::class)
        fun available(dis: InputStream?) {
            if (dis == null) {
                println("available=null")
                return
            }

            val size = dis.available()
            val data = ByteArray(size)
            if (dis.read(data) != size) {
                throw IOException("Read available failed.")
            }
            inspect(data, "Available")
        }

        const val WPE = 16
        const val MNM = 20

        @JvmStatic
        fun inspectMapData(label: String, data: Array<ShortArray>) {
            inspectMapData(label, data, -1)
        }

        @JvmStatic
        fun inspectMapData(label: String, data: Array<ShortArray>, filter: Int) {
            val buffer = StringBuffer()
            buffer.append("\n>-----------------------------------------------------------------------------<\n")

            buffer.append(SimpleDateFormat("[HH:mm:ss SSS]").format(Date()))

            buffer.append(data.size)
            if (data.size > 0) {
                buffer.append('x').append(data[0].size)
            }
            buffer.append(label).append('\n')

            for (dt in data) {
                for (ds in dt) {
                    val di = ds.toInt() and 0xFFFF

                    if (di == filter) {
                        buffer.append("     ")
                        continue
                    }

                    val hex = Integer.toString(di, 16).toUpperCase()
                    var n = 0
                    while (n < 4 - hex.length) {
                        buffer.append('0')
                        n++
                    }
                    buffer.append(hex)
                    buffer.append(' ')
                }
                buffer.append('\n')
            }

            buffer.append("^-----------------------------------------------------------------------------^")
            println(buffer)
        }

        @JvmStatic
        fun inspect(label: String, data: Array<ByteArray>) {
            inspect(label, data, -1)
        }

        @JvmStatic
        fun inspect(label: String, data: ShortArray) {
            println(inspectString(null, label, data, WPE))
        }

        @JvmStatic
        fun inspectString(date: Date?, label: String, data: ShortArray?, mode: Int): String {
            var date = date
            val buffer = StringBuilder()
            buffer.append("\n>-----------------------------------------------------------------------------<\n")

            if (date == null) {
                date = Date()
            }
            buffer.append(SimpleDateFormat("[HH:mm:ss SSS]").format(date))

            buffer.append(label)

            buffer.append("\nsize: ")
            if (data != null) {
                buffer.append(data.size)
            } else {
                buffer.append("null")
            }
            buffer.append('\n')

            if (data != null) {
                var i = 0
                while (i < data.size) {
                    val di = data[i].toInt() and 0xFFFF

                    val hex = Integer.toString(di, 16).toUpperCase()
                    var n = 0
                    while (n < 4 - hex.length) {
                        buffer.append('0')
                        n++
                    }
                    buffer.append(hex)
                    buffer.append(' ')

                    if ((i + 1) % mode == 0) {
                        buffer.append('\n')
                    }
                    i++
                }

                if (i % mode != 0) {
                    buffer.append('\n')
                }
            }

            buffer.append("^-----------------------------------------------------------------------------^")

            return buffer.toString()
        }

        @JvmStatic
        fun inspect(label: String, data: Array<ByteArray>, filter: Int) {
            println(inspectString(label, data, filter))
        }

        @JvmStatic
        fun inspect(date: Date?, label: String, data: ByteArray?, mode: Int) {
            println(inspectInternal(date, label, data, mode))
        }

        private fun inspectInternal(date: Date?, label: String, data: ByteArray?, mode: Int): String {
            var date = date
            val buffer = StringBuilder()
            buffer.append("\n>-----------------------------------------------------------------------------<\n")

            if (date == null) {
                date = Date()
            }
            buffer.append(SimpleDateFormat("[HH:mm:ss SSS]").format(date))

            buffer.append(label)
            if (data != null) {
                buffer.append(", md5=").append(Hex.encodeHex(DigestUtils.md5(data)))
                if (data.size < 1024) {
                    buffer.append(", hex=").append(Hex.encodeHex(data))
                }
            }

            buffer.append("\nsize: ")
            if (data != null) {
                buffer.append(data.size)
            } else {
                buffer.append("null")
            }
            buffer.append('\n')

            if (data != null) {
                var i = 0
                while (i < data.size) {
                    if (i % mode == 0) {
                        val hex = Integer.toHexString(i % 0x10000).toUpperCase()
                        var k = 0
                        val fill = 4 - hex.length
                        while (k < fill) {
                            buffer.append('0')
                            k++
                        }
                        buffer.append(hex).append(": ")
                    }

                    val di = data[i].toInt() and 0xFF
                    val hex = Integer.toString(di, 16).toUpperCase()
                    if (hex.length < 2) {
                        buffer.append('0')
                    }
                    buffer.append(hex)
                    buffer.append(' ')

                    if ((i + 1) % mode == 0) {
                        buffer.append("   ")
                        var k = i - 15
                        while (k < i + 1) {
                            buffer.append(toChar(data[k]))
                            k++
                        }
                        buffer.append('\n')
                    }
                    i++
                }

                val redex = mode - i % mode
                var k: Byte = 0
                while (k < redex && redex < mode) {
                    buffer.append("  ")
                    buffer.append(' ')
                    k++
                }
                val count = i % mode
                val start = i - count
                if (start < i) {
                    buffer.append("   ")
                }
                var kk = start
                while (kk < i) {
                    buffer.append(toChar(data[kk]))
                    kk++
                }

                if (redex < mode) {
                    buffer.append('\n')
                }
            }

            buffer.append("^-----------------------------------------------------------------------------^")

            return buffer.toString()
        }

        @JvmStatic
        fun inspect(label: String, data: ByteArray, mode: Int) {
            inspect(null, label, data, mode)
        }

        /**
         * Inspects a buffer, labelling it as sent or received traffic.
         */
        @JvmStatic
        fun inspect(data: ByteArray, send: Boolean) {
            inspect(if (send) "发送数据" else "接收数据", data, WPE)
        }

        /**
         * Inspects a buffer, prefixing the label with the given type in hex.
         */
        @JvmStatic
        fun inspect(type: Int, data: ByteArray, send: Boolean) {
            val ts = Integer.toHexString(type).toUpperCase()
            inspect(if (send) "发送数据：0x" + ts else "接收数据：0x" + ts, data, WPE)
        }

        /**
         * Inspects a buffer under the given label.
         */
        @JvmStatic
        fun inspect(data: ByteArray, label: String) {
            inspect(label, data, WPE)
        }

        private fun toChar(`in`: Byte): Char {
            if (`in`.toInt() == ' '.code)
                return ' '

            return if (`in`.toInt() > 0x7E || `in`.toInt() < 0x21)
                '.'
            else
                `in`.toInt().toChar()
        }

        /**
         * Prints the runtime type (and value) of the given object.
         */
        @JvmStatic
        fun objectType(`in`: Any?) {
            if (`in` == null) {
                println("Object type is null")
                return
            }

            println("Object type is " + `in`.javaClass + '[' + `in` + ']')
        }

        /**
         * Prints an int value in hex under the given label.
         */
        @JvmStatic
        fun inspect(label: String, value: Int) {
            println(label + "0x" + Integer.toHexString(value).toUpperCase())
        }

        /**
         * Throws an auxiliary [Error]; useful as a breakpoint/trap during debugging.
         */
        @JvmStatic
        fun throwError() {
            throw Error("auxiliary error")
        }

        /**
         * Throws an auxiliary [Error] only when [testValue] matches [errorValue].
         */
        @JvmStatic
        fun throwError(errorValue: Int, testValue: Int) {
            if (testValue != errorValue) {
                return
            }
            throw Error("auxiliary error")
        }

        @JvmStatic
        fun where() {
            Thread.dumpStack()
        }

        @JvmStatic
        fun where(testValue: Int, printValue: Int) {
            if (testValue != printValue) {
                return
            }

            where()
        }

        @JvmStatic
        protected fun close(`is`: InputStream?) {
            if (`is` == null) {
                return
            }

            try {
                `is`.close()
            } catch (ignored: Exception) {
            }
        }

        @JvmStatic
        protected fun close(os: OutputStream?) {
            if (os == null) {
                return
            }

            try {
                os.close()
            } catch (ignored: Exception) {
            }
        }

        @JvmStatic
        fun inspectString(label: String, data: Array<ByteArray>): String {
            return inspectString(label, data, -1)
        }

        @JvmStatic
        fun inspectString(label: String, data: ShortArray): String {
            return inspectString(Date(), label, data)
        }

        @JvmStatic
        fun inspectString(date: Date?, label: String, data: ShortArray): String {
            return inspectString(date, label, data, WPE)
        }

        @JvmStatic
        fun inspectString(label: String, data: Array<ByteArray>, filter: Int): String {
            val buffer = StringBuilder()
            buffer.append("\n>-----------------------------------------------------------------------------<\n")

            buffer.append(SimpleDateFormat("[HH:mm:ss SSS]").format(Date()))

            if (data.size > 0) {
                buffer.append(data[0].size).append('x')
            }
            buffer.append(data.size)
            buffer.append(label).append('\n')

            for (dt in data) {
                for (db in dt) {
                    val di = db.toInt() and 0xFF

                    if (di == filter) {
                        buffer.append("   ")
                        continue
                    }

                    val hex = Integer.toString(di, 16).toUpperCase()
                    if (hex.length < 2) {
                        buffer.append('0')
                    }
                    buffer.append(hex)
                    buffer.append(' ')
                }
                buffer.append('\n')
            }

            buffer.append("^-----------------------------------------------------------------------------^")
            return buffer.toString()
        }

        @JvmStatic
        fun inspectString(label: String, data: ByteArray, mode: Int): String {
            return inspectString(null, label, data, mode)
        }

        @JvmStatic
        fun inspectString(date: Date?, label: String, data: ByteArray?, mode: Int): String {
            return inspectInternal(date, label, data, mode)
        }

        /**
         * Renders an inspection of a buffer as a string, labelled as sent or received traffic.
         */
        @JvmStatic
        fun inspectString(data: ByteArray, send: Boolean): String {
            return inspectString(if (send) "Sent" else "Received", data, WPE)
        }

        /**
         * Renders an inspection of a buffer, prefixing the label with the given type in hex.
         */
        @JvmStatic
        fun inspectString(type: Int, data: ByteArray, send: Boolean): String {
            val ts = Integer.toHexString(type).toUpperCase()
            return inspectString(if (send) "发送数据: 0x" + ts else "接收数据: 0x" + ts, data, WPE)
        }

        /**
         * Renders an inspection of a buffer under the given label.
         */
        @JvmStatic
        fun inspectString(data: ByteArray, label: String): String {
            return inspectString(label, data, WPE)
        }

    }

}
