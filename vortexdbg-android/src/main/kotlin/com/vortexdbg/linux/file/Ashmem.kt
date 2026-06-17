package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgPointer
import org.slf4j.LoggerFactory

internal class Ashmem internal constructor(emulator: Emulator<*>, oflags: Int, path: String) : DriverFileIO(emulator, oflags, path) {

    private var name: String? = null
    private var size: Int = 0

    override fun ioctl(emulator: Emulator<*>, request: Long, argp: Long): Int {
        if (request == ASHMEM_SET_NAME.toLong()) {
            val pointer = VortexdbgPointer.pointer(emulator, argp)
            assert(pointer != null)
            this.name = pointer!!.getString(0L)
            log.debug("ashmem set name: {}", this.name)
            return 0
        }
        if (request == ASHMEM_SET_SIZE_32.toLong() || request == ASHMEM_SET_SIZE_64.toLong()) {
            this.size = argp.toInt()
            log.debug("ashmem set size: {}", this.size)
            return 0
        }

        return super.ioctl(emulator, request, argp)
    }

    override fun getMmapData(addr: Long, offset: Int, length: Int): ByteArray {
        return ByteArray(0)
    }

    override fun toString(): String {
        return "Ashmem{" +
                "name='" + name + '\'' +
                ", size=" + size +
                '}'
    }

    companion object {
        private val log = LoggerFactory.getLogger(Ashmem::class.java)

        private const val ASHMEM_SET_NAME = 0x41007701
        private const val ASHMEM_SET_SIZE_32 = 0x40047703
        private const val ASHMEM_SET_SIZE_64 = 0x40087703
    }
}
