package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.file.FileIO
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.file.linux.BaseAndroidFileIO
import com.sun.jna.Pointer

import java.io.IOException
import java.util.Arrays

open class Stdin(oflags: Int) : BaseAndroidFileIO(oflags), AndroidFileIO {

    init {
        stdio = true
    }

    override fun close() {
    }

    override fun write(data: ByteArray): Int {
        throw AbstractMethodError(String(data))
    }

    override fun read(backend: Backend, buffer: Pointer, count: Int): Int {
        try {
            val data = ByteArray(count)
            val read = System.`in`.read(data, 0, count)
            if (read <= 0) {
                return read
            }

            buffer.write(0, Arrays.copyOf(data, read), 0, read)
            return read
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun fstat(emulator: Emulator<*>, stat: com.vortexdbg.file.linux.StatStructure): Int {
        stat.st_mode = 0x0
        stat.st_size = 0L
        stat.pack()
        return 0
    }

    override fun dup2(): FileIO {
        return this
    }

    override fun ioctl(emulator: Emulator<*>, request: Long, argp: Long): Int {
        return 0
    }

    override fun getdents64(dirp: Pointer, size: Int): Int {
        throw UnsupportedOperationException()
    }

    override fun toString(): String {
        return "stdin"
    }
}
