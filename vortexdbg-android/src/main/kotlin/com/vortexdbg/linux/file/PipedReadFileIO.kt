package com.vortexdbg.linux.file

import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.file.linux.BaseAndroidFileIO
import com.vortexdbg.file.linux.IOConstants
import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory

import java.io.IOException
import java.io.PipedInputStream
import java.util.Arrays

open class PipedReadFileIO(private val inputStream: PipedInputStream, private val writefd: Int) : BaseAndroidFileIO(IOConstants.O_RDONLY), AndroidFileIO {

    override fun read(backend: Backend, buffer: Pointer, count: Int): Int {
        try {
            val receiveBuf = ByteArray(Math.min(count, inputStream.available()))
            val read = inputStream.read(receiveBuf, 0, receiveBuf.size)
            if (read <= 0) {
                return read
            }

            val data = Arrays.copyOf(receiveBuf, read)
            buffer.write(0L, data, 0, data.size)
            if (log.isDebugEnabled) {
                log.debug(Inspector.inspectString(data, "read fd=$writefd"))
            }
            return data.size
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun canRead(): Boolean {
        try {
            return inputStream.available() > 0
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun close() {
        try {
            inputStream.close()
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun toString(): String {
        return "PipedRead: $writefd"
    }

    companion object {
        private val log = LoggerFactory.getLogger(PipedReadFileIO::class.java)
    }
}
