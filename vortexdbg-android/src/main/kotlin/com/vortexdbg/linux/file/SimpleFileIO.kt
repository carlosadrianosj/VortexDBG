package com.vortexdbg.linux.file

import com.alibaba.fastjson.util.IOUtils
import com.vortexdbg.Emulator
import com.vortexdbg.Utils
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.file.FileIO
import com.vortexdbg.file.NewFileIO
import com.vortexdbg.file.linux.BaseAndroidFileIO
import com.vortexdbg.file.linux.IOConstants
import com.vortexdbg.file.linux.StatStructure
import com.vortexdbg.unix.IO
import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files

open class SimpleFileIO(oflags: Int, @JvmField protected val file: File, @JvmField protected val path: String) : BaseAndroidFileIO(oflags), NewFileIO {

    private var _randomAccessFile: RandomAccessFile? = null

    @Synchronized
    private fun checkOpenFile(): RandomAccessFile {
        try {
            if (_randomAccessFile == null) {
                FileUtils.forceMkdir(file.parentFile)
                if (!file.exists() && !file.createNewFile()) {
                    throw IOException("createNewFile failed: $file")
                }
                _randomAccessFile = RandomAccessFile(file, "rws")
                onFileOpened(_randomAccessFile!!)
            }
            return _randomAccessFile!!
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    init {
        if (file.isDirectory) {
            throw IllegalArgumentException("file is directory: $file")
        }
        if (!file.exists()) {
            throw IllegalArgumentException("file not exists: $file")
        }
    }

    @Throws(IOException::class)
    internal open fun onFileOpened(randomAccessFile: RandomAccessFile) {
    }

    override fun close() {
        IOUtils.close(_randomAccessFile)

        if (debugStream != null) {
            try {
                debugStream!!.flush()
            } catch (ignored: IOException) {
            }
        }
    }

    override fun write(data: ByteArray): Int {
        try {
            if (debugStream != null) {
                debugStream!!.write(data)
                debugStream!!.flush()
            }

            if (log.isDebugEnabled && data.size < 0x3000) {
                Inspector.inspect(data, "write")
            }

            val randomAccessFile = checkOpenFile()
            if ((oflags and IOConstants.O_APPEND) != 0) {
                randomAccessFile.seek(randomAccessFile.length())
            }
            randomAccessFile.write(data)
            return data.size
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    @JvmField
    internal var debugStream: OutputStream? = null

    internal fun setDebugStream(stream: OutputStream) {
        this.debugStream = BufferedOutputStream(stream)
    }

    override fun read(backend: Backend, pointer: Pointer, _count: Int): Int {
        val randomAccessFile = checkOpenFile()
        return Utils.readFile(randomAccessFile, pointer, _count)
    }

    override fun fstat(emulator: Emulator<*>, stat: StatStructure): Int {
        val st_mode: Int
        if (IO.STDOUT == file.name) {
            st_mode = IO.S_IFCHR or 0x777
        } else if (Files.isSymbolicLink(file.toPath())) {
            st_mode = IO.S_IFLNK
        } else {
            st_mode = IO.S_IFREG
        }
        stat.st_dev = 1L
        stat.st_mode = st_mode
        stat.st_uid = 0
        stat.st_gid = 0
        stat.st_size = file.length()
        stat.st_blksize = emulator.getPageAlign()
        stat.st_ino = 1L
        stat.st_blocks = ((file.length() + emulator.getPageAlign() - 1) / emulator.getPageAlign())
        stat.setLastModification(file.lastModified())
        stat.pack()
        return 0
    }

    @Throws(IOException::class)
    override fun getMmapData(addr: Long, offset: Int, length: Int): ByteArray {
        val randomAccessFile = checkOpenFile()
        randomAccessFile.seek(offset.toLong())
        val remaining = (randomAccessFile.length() - randomAccessFile.filePointer).toInt()
        val baos = if (remaining <= 0) ByteArrayOutputStream() else ByteArrayOutputStream(Math.min(length, remaining))
        val buf = ByteArray(1024)
        do {
            var count = length - baos.size()
            if (count == 0) {
                break
            }

            if (count > buf.size) {
                count = buf.size
            }

            val read = randomAccessFile.read(buf, 0, count)
            if (read == -1) {
                break
            }

            baos.write(buf, 0, read)
        } while (true)
        return baos.toByteArray()
    }

    override fun toString(): String {
        return path
    }

    override fun ioctl(emulator: Emulator<*>, request: Long, argp: Long): Int {
        if (IO.STDOUT == path || IO.STDERR == path) {
            return 0
        }

        return super.ioctl(emulator, request, argp)
    }

    override fun dup2(): FileIO {
        val dup = SimpleFileIO(oflags, file, path)
        dup.debugStream = debugStream
        dup.op = op
        dup.oflags = oflags
        return dup
    }

    override fun lseek(offset: Int, whence: Int): Int {
        try {
            val randomAccessFile = checkOpenFile()
            when (whence) {
                FileIO.SEEK_SET -> {
                    randomAccessFile.seek(offset.toLong())
                    return randomAccessFile.filePointer.toInt()
                }
                FileIO.SEEK_CUR -> {
                    randomAccessFile.seek(randomAccessFile.filePointer + offset)
                    return randomAccessFile.filePointer.toInt()
                }
                FileIO.SEEK_END -> {
                    randomAccessFile.seek(randomAccessFile.length() + offset)
                    return randomAccessFile.filePointer.toInt()
                }
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }

        return super.lseek(offset, whence)
    }

    override fun llseek(offset: Long, result: Pointer, whence: Int): Int {
        try {
            val randomAccessFile = checkOpenFile()
            when (whence) {
                FileIO.SEEK_SET -> {
                    randomAccessFile.seek(offset)
                    result.setLong(0L, randomAccessFile.filePointer)
                    return 0
                }
                FileIO.SEEK_END -> {
                    randomAccessFile.seek(randomAccessFile.length() - offset)
                    result.setLong(0L, randomAccessFile.filePointer)
                    return 0
                }
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }

        return super.llseek(offset, result, whence)
    }

    override fun ftruncate(length: Int): Int {
        try {
            FileOutputStream(file, true).channel.use { channel ->
                channel.truncate(length.toLong())
                return 0
            }
        } catch (e: IOException) {
            log.debug("ftruncate failed", e)
            return -1
        }
    }

    override fun getPath(): String {
        return path
    }

    companion object {
        private val log = LoggerFactory.getLogger(SimpleFileIO::class.java)
    }
}
