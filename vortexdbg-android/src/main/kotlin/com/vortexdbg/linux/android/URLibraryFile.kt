package com.vortexdbg.linux.android

import com.vortexdbg.Emulator
import com.vortexdbg.Utils
import com.vortexdbg.spi.LibraryFile
import org.apache.commons.io.IOUtils

import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer

open class URLibraryFile(
    private val url: URL,
    private val name: String,
    private val sdk: Int,
    private val is64Bit: Boolean
) : LibraryFile {

    override fun getName(): String {
        return name
    }

    override fun getMapRegionName(): String {
        return getPath()
    }

    override fun resolveLibrary(emulator: Emulator<*>, soName: String): LibraryFile? {
        if (sdk <= 0) {
            return null
        }
        return AndroidResolver.resolveLibrary(emulator, soName, sdk)
    }

    @Throws(IOException::class)
    override fun mapBuffer(): ByteBuffer {
        return if ("file".equals(url.protocol, ignoreCase = true)) {
            Utils.mapBuffer(File(url.path))
        } else {
            ByteBuffer.wrap(IOUtils.toByteArray(url))
        }
    }

    override fun getFileSize(): Long {
        return if ("file".equals(url.protocol, ignoreCase = true)) {
            File(url.path).length()
        } else {
            try {
                IOUtils.toByteArray(url).size.toLong()
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        }
    }

    override fun getPath(): String {
        return "/system/" + (if (is64Bit) "lib64/" else "lib/") + name
    }
}
