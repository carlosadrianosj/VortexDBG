package com.vortexdbg.linux.android

import com.vortexdbg.Emulator
import com.vortexdbg.spi.LibraryFile

import java.nio.ByteBuffer

open class ElfLibraryRawFile(name: String?, buffer: ByteBuffer, private val is64Bit: Boolean) : LibraryFile {

    private val raw: ByteBuffer = buffer
    private val name: String =
        if (name == null || name.isEmpty()) String.format("%x.so", buffer.hashCode()) else name

    constructor(name: String?, binary: ByteArray, is64Bit: Boolean) : this(name, ByteBuffer.wrap(binary), is64Bit)

    override fun getFileSize(): Long {
        return raw.capacity().toLong()
    }

    override fun getName(): String {
        return name
    }

    override fun getMapRegionName(): String {
        return getPath()
    }

    override fun resolveLibrary(emulator: Emulator<*>, soName: String): LibraryFile? {
        return null
    }

    override fun mapBuffer(): ByteBuffer {
        return raw
    }

    override fun getPath(): String {
        return "/system/" + (if (is64Bit) "lib64/" else "lib/") + name
    }
}
