package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.file.FileIO
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.file.linux.BaseAndroidFileIO
import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer

open class DumpFileIO(private val fd: Int) : BaseAndroidFileIO(0), AndroidFileIO {

    override fun write(data: ByteArray): Int {
        Inspector.inspect(data, "Dump for fd: $fd")
        return data.size
    }

    override fun close() {
    }

    override fun dup2(): FileIO {
        return this
    }

    override fun fstat(emulator: Emulator<*>, stat: com.vortexdbg.file.linux.StatStructure): Int {
        throw UnsupportedOperationException()
    }

    override fun getdents64(dirp: Pointer, size: Int): Int {
        throw UnsupportedOperationException()
    }
}
