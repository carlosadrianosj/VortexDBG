package com.vortexdbg.memory

import com.vortexdbg.Emulator
import com.vortexdbg.spi.LibraryFile

import java.io.IOException
import java.nio.ByteBuffer

open class MemRegion(
    @JvmField val virtualAddress: Long,
    @JvmField val begin: Long,
    @JvmField val end: Long,
    @JvmField val perms: Int,
    private val libraryFile: LibraryFile?,
    @JvmField val offset: Long
) : Comparable<MemRegion> {

    open fun getName(): String {
        return libraryFile!!.getMapRegionName()
    }

    @Throws(IOException::class)
    fun readLibrary(): ByteArray {
        val buffer = libraryFile!!.mapBuffer()
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return data
    }

    override fun compareTo(o: MemRegion): Int {
        return java.lang.Long.compare(begin, o.begin)
    }

    companion object {
        @JvmStatic
        fun create(begin: Long, size: Int, perms: Int, name: String): MemRegion {
            return MemRegion(begin, begin, begin + size, perms, object : LibraryFile {
                override fun getName(): String {
                    return name
                }

                override fun getMapRegionName(): String {
                    return name
                }

                override fun resolveLibrary(emulator: Emulator<*>, soName: String): LibraryFile {
                    throw UnsupportedOperationException()
                }

                override fun mapBuffer(): ByteBuffer {
                    throw UnsupportedOperationException()
                }

                override fun getPath(): String {
                    return name
                }

                override fun getFileSize(): Long {
                    throw UnsupportedOperationException()
                }
            }, 0)
        }
    }
}
