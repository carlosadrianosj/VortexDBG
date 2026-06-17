package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.file.linux.LinuxFileSystem
import com.vortexdbg.linux.android.LogCatLevel
import org.slf4j.LoggerFactory

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

open class LogCatFileIO(private val emulator: Emulator<*>, oflags: Int, file: File, path: String) : SimpleFileIO(oflags, file, path) {

    private val type: String = path.substring(LOG_PATH_PREFIX.length)

    init {
        if (log.isDebugEnabled) {
            setDebugStream(System.out)
        }
    }

    @Throws(IOException::class)
    override fun onFileOpened(randomAccessFile: RandomAccessFile) {
        super.onFileOpened(randomAccessFile)

        randomAccessFile.channel.truncate(0)
    }

    private val byteArrayOutputStream = ByteArrayOutputStream()

    override fun write(data: ByteArray): Int {
        try {
            byteArrayOutputStream.write(data)

            if (byteArrayOutputStream.size() <= 1) {
                return data.size
            }

            var tagIndex = -1
            var bodyIndex = -1
            val body = byteArrayOutputStream.toByteArray()
            for (i in 1 until body.size) {
                if (body[i].toInt() != 0) {
                    continue
                }

                if (tagIndex == -1) {
                    tagIndex = i
                    continue
                }

                bodyIndex = i
                break
            }

            if (tagIndex != -1 && bodyIndex != -1) {
                byteArrayOutputStream.reset()

                val level = body[0].toInt() and 0xff
                val tag = String(body, 1, tagIndex - 1)
                val text = String(body, tagIndex + 1, bodyIndex - tagIndex - 1)
                val value = LogCatLevel.valueOf(level)
                super.write(String.format("%s/%s: %s\n", value, tag, text).toByteArray())

                val fileSystem = emulator.getFileSystem() as LinuxFileSystem
                val handler = fileSystem.getLogCatHandler()
                if (handler != null) {
                    handler.handleLog(type, value, tag, text)
                }
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
        return data.size
    }

    companion object {
        private val log = LoggerFactory.getLogger(LogCatFileIO::class.java)

        const val LOG_PATH_PREFIX = "/dev/log/"
    }
}
