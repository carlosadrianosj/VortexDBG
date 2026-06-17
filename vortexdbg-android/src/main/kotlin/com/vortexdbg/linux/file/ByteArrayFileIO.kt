package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.file.FileIO
import com.vortexdbg.file.linux.BaseAndroidFileIO
import com.vortexdbg.file.linux.StatStructure
import com.vortexdbg.unix.IO
import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory

import java.util.Arrays

open class ByteArrayFileIO(oflags: Int, @JvmField protected val path: String, @JvmField protected val bytes: ByteArray) : BaseAndroidFileIO(oflags) {

    private var pos: Int = 0

    override fun close() {
        pos = 0
    }

    override fun write(data: ByteArray): Int {
        throw UnsupportedOperationException()
    }

    override fun pread(backend: Backend, buffer: Pointer, count: Int, offset: Long): Int {
        val pos = this.pos
        try {
            this.pos = offset.toInt()
            return read(backend, buffer, count)
        } finally {
            this.pos = pos
        }
    }

    override fun read(backend: Backend, buffer: Pointer, count: Int): Int {
        var count = count
        if (pos >= bytes.size) {
            return 0
        }

        val remain = bytes.size - pos
        if (count > remain) {
            count = remain
        }
        buffer.write(0L, bytes, pos, count)
        if (log.isDebugEnabled) {
            log.debug(Inspector.inspectString(Arrays.copyOfRange(bytes, pos, pos + count), "read path=" + path + ", fp=" + pos + ", _count=" + count + ", length=" + bytes.size + ", buffer=" + buffer))
        }
        pos += count

        return count
    }

    override fun lseek(offset: Int, whence: Int): Int {
        when (whence) {
            FileIO.SEEK_SET -> {
                pos = offset
                return pos
            }
            FileIO.SEEK_CUR -> {
                pos += offset
                return pos
            }
            FileIO.SEEK_END -> {
                pos = bytes.size + offset
                return pos
            }
        }
        return super.lseek(offset, whence)
    }

    override fun fstat(emulator: Emulator<*>, stat: StatStructure): Int {
        stat.st_dev = 1L
        stat.st_mode = IO.S_IFREG
        stat.st_uid = 0
        stat.st_gid = 0
        stat.st_size = bytes.size.toLong()
        stat.st_blksize = emulator.getPageAlign()
        stat.st_blocks = ((bytes.size + emulator.getPageAlign() - 1) / emulator.getPageAlign()).toLong()
        stat.st_ino = 1L
        stat.setLastModification(System.currentTimeMillis())
        stat.pack()
        return 0
    }

    override fun getMmapData(addr: Long, offset: Int, length: Int): ByteArray {
        return if (offset == 0 && length == bytes.size) {
            bytes
        } else {
            val data = ByteArray(length)
            System.arraycopy(bytes, offset, data, 0, data.size)
            data
        }
    }

    override fun ioctl(emulator: Emulator<*>, request: Long, argp: Long): Int {
        return 0
    }

    override fun toString(): String {
        return path
    }

    override fun ftruncate(length: Int): Int {
        return 0
    }

    companion object {
        private val log = LoggerFactory.getLogger(ByteArrayFileIO::class.java)
    }
}
