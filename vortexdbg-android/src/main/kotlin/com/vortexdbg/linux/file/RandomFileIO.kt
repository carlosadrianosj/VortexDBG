package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.file.linux.IOConstants
import com.sun.jna.Pointer

import java.util.concurrent.ThreadLocalRandom

open class RandomFileIO(emulator: Emulator<*>, path: String) : DriverFileIO(emulator, IOConstants.O_RDONLY, path) {

    override fun read(backend: Backend, buffer: Pointer, count: Int): Int {
        var total = 0
        val buf = ByteArray(Math.min(0x1000, count))
        randBytes(buf)
        var pointer = buffer
        while (total < count) {
            val read = Math.min(buf.size, count - total)
            pointer.write(0L, buf, 0, read)
            total += read
            pointer = pointer.share(read.toLong())
        }
        return total
    }

    protected open fun randBytes(bytes: ByteArray) {
        ThreadLocalRandom.current().nextBytes(bytes)
    }
}
