package com.vortexdbg.linux.file

import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.file.NewFileIO
import com.vortexdbg.file.linux.BaseAndroidFileIO
import com.vortexdbg.file.linux.IOConstants
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory

import java.nio.ByteBuffer
import java.nio.ByteOrder

open class EventFD(initval: Int, private val semaphore: Boolean, private val nonblock: Boolean) : BaseAndroidFileIO(IOConstants.O_RDWR), NewFileIO {

    private var counter: Long = initval.toLong()

    override fun read(backend: Backend, buffer: Pointer, count: Int): Int {
        if (count != 8) {
            return super.read(backend, buffer, count)
        }
        if (counter == 0L) {
            if (nonblock) {
                return -1
            } else {
                throw UnsupportedOperationException()
            }
        }
        if (semaphore) {
            buffer.setLong(0L, 1)
            counter--
        } else {
            buffer.setLong(0L, counter)
            counter = 0
        }
        return 8
    }

    override fun write(data: ByteArray): Int {
        if (data.size != 8) {
            return super.write(data)
        }

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val cnt = buffer.getLong()
        counter += cnt
        log.debug("write cnt={}, counter={}", cnt, counter)
        return 8
    }

    override fun close() {
    }

    companion object {
        private val log = LoggerFactory.getLogger(EventFD::class.java)
    }
}
