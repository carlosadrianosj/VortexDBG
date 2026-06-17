package com.vortexdbg.linux.android

import com.vortexdbg.Emulator
import com.vortexdbg.Utils
import com.vortexdbg.spi.LibraryFile

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

open class ElfLibraryFile(private val elfFile: File, private val is64Bit: Boolean) : LibraryFile {

    override fun getFileSize(): Long {
        return elfFile.length()
    }

    override fun getName(): String {
        return elfFile.name
    }

    override fun getMapRegionName(): String {
        return getPath()
    }

    override fun resolveLibrary(emulator: Emulator<*>, soName: String): LibraryFile? {
        val file = File(elfFile.parentFile, soName)
        return if (file.canRead()) ElfLibraryFile(file, is64Bit) else null
    }

    @Throws(IOException::class)
    override fun mapBuffer(): ByteBuffer {
        return Utils.mapBuffer(elfFile)
    }

    override fun getPath(): String {
        val name = getName()
        return if (name.endsWith(".so")) {
            "/system/" + (if (is64Bit) "lib64/" else "lib/") + name
        } else {
            "/system/bin/$name"
        }
    }

}
