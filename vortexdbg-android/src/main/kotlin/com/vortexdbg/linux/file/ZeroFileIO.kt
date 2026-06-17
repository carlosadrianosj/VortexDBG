package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.sun.jna.Pointer

open class ZeroFileIO(emulator: Emulator<*>, oflags: Int, path: String) : DriverFileIO(emulator, oflags, path) {

    override fun read(backend: Backend, buffer: Pointer, count: Int): Int {
        var total = 0
        val buf = ByteArray(Math.min(0x1000, count))
        var pointer = buffer
        while (total < count) {
            val read = Math.min(buf.size, count - total)
            pointer.write(0L, buf, 0, read)
            total += read
            pointer = pointer.share(read.toLong())
        }
        return total
    }

    override fun write(data: ByteArray): Int {
        return data.size
    }

    override fun getMmapData(addr: Long, offset: Int, length: Int): ByteArray {
        return ByteArray(length)
    }
}
