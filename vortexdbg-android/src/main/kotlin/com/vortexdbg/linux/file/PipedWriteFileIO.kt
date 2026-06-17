package com.vortexdbg.linux.file

import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.file.linux.BaseAndroidFileIO
import com.vortexdbg.file.linux.IOConstants
import com.vortexdbg.utils.Inspector
import org.slf4j.LoggerFactory

import java.io.IOException
import java.io.PipedOutputStream

open class PipedWriteFileIO(private val outputStream: PipedOutputStream, private val writefd: Int) : BaseAndroidFileIO(IOConstants.O_WRONLY), AndroidFileIO {

    override fun write(data: ByteArray): Int {
        try {
            if (log.isDebugEnabled) {
                log.debug(Inspector.inspectString(data, "write fd=$writefd"))
            }
            outputStream.write(data)
            return data.size
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun close() {
        try {
            outputStream.close()
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun toString(): String {
        return "PipedWrite: $writefd"
    }

    companion object {
        private val log = LoggerFactory.getLogger(PipedWriteFileIO::class.java)
    }
}
