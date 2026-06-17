package com.vortexdbg.linux.file

import com.alibaba.fastjson.util.IOUtils
import com.vortexdbg.file.FileIO
import com.vortexdbg.file.StdoutCallback
import org.slf4j.LoggerFactory

import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.io.RandomAccessFile

open class Stdout(oflags: Int, file: File, path: String, private val err: Boolean, private val callback: StdoutCallback?) : SimpleFileIO(oflags, file, path) {

    private val out: PrintStream = if (err) System.err else System.out

    init {
        if (log.isDebugEnabled) {
            setDebugStream(if (err) System.err else System.out)
        }

        stdio = true
    }

    override fun close() {
        super.close()

        IOUtils.close(output)
    }

    private var output: RandomAccessFile? = null

    override fun write(data: ByteArray): Int {
        try {
            if (output == null) {
                output = RandomAccessFile(file, "rw")
                output!!.channel.truncate(0)
            }

            if (debugStream != null) {
                debugStream!!.write(data)
            }
            var enablePrint = true
            if (callback != null) {
                enablePrint = callback.notifyOut(data, err)
            }
            if (enablePrint && log.isWarnEnabled) {
                out.write(data)
                out.flush()
            }

            output!!.write(data)
            return data.size
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun lseek(offset: Int, whence: Int): Int {
        try {
            when (whence) {
                FileIO.SEEK_SET -> {
                    output!!.seek(offset.toLong())
                    return output!!.filePointer.toInt()
                }
                FileIO.SEEK_CUR -> {
                    output!!.seek(output!!.filePointer + offset)
                    return output!!.filePointer.toInt()
                }
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }

        return super.lseek(offset, whence)
    }

    override fun ftruncate(length: Int): Int {
        try {
            output!!.channel.truncate(length.toLong())
            return 0
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun dup2(): FileIO {
        val dup = Stdout(0, file, path, err, callback)
        dup.debugStream = debugStream
        dup.op = op
        dup.oflags = oflags
        return dup
    }

    companion object {
        private val log = LoggerFactory.getLogger(Stdout::class.java)
    }
}
