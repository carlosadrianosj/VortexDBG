package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.file.FileIO
import com.vortexdbg.file.linux.BaseAndroidFileIO
import com.vortexdbg.file.linux.IOConstants
import com.vortexdbg.file.linux.StatStructure
import com.sun.jna.Pointer

import java.io.IOException
import java.util.Arrays

open class NullFileIO(private val path: String) : BaseAndroidFileIO(IOConstants.O_RDWR), FileIO {

    private fun isTTY(): Boolean {
        return "/dev/tty" == path
    }

    override fun close() {
    }

    override fun write(data: ByteArray): Int {
        if (isTTY()) {
            try {
                System.out.write(data)
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        }

        return data.size
    }

    override fun lseek(offset: Int, whence: Int): Int {
        return 0
    }

    override fun read(backend: Backend, buffer: Pointer, count: Int): Int {
        if (isTTY()) {
            try {
                val buf = ByteArray(count)
                val read = System.`in`.read(buf)
                if (read <= 0) {
                    return read
                }
                buffer.write(0, Arrays.copyOf(buf, read), 0, read)
                return read
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        }
        return 0
    }

    override fun fstat(emulator: Emulator<*>, stat: StatStructure): Int {
        stat.st_size = 0L
        stat.st_blksize = 0
        stat.pack()
        return 0
    }

    override fun ioctl(emulator: Emulator<*>, request: Long, argp: Long): Int {
        return 0
    }

    override fun toString(): String {
        return path
    }
}
