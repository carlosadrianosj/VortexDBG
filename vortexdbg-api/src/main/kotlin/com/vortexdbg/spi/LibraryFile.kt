package com.vortexdbg.spi

import com.vortexdbg.Emulator

import java.io.IOException
import java.nio.ByteBuffer

interface LibraryFile {

    fun getName(): String

    fun getMapRegionName(): String

    @Throws(IOException::class)
    fun resolveLibrary(emulator: Emulator<*>, soName: String): LibraryFile?

    @Throws(IOException::class)
    fun mapBuffer(): ByteBuffer

    fun getPath(): String

    fun getFileSize(): Long

}
