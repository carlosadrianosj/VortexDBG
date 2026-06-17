package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.arm.ARM
import com.vortexdbg.file.VortexdbgFileFilter
import com.vortexdbg.file.linux.BaseAndroidFileIO
import com.vortexdbg.file.linux.StatStructure
import com.vortexdbg.linux.struct.Dirent
import com.vortexdbg.linux.struct.StatFS
import com.vortexdbg.unix.IO
import com.sun.jna.Pointer

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections

open class DirectoryFileIO : BaseAndroidFileIO {

    enum class DirentType(type: Int) {
        DT_FIFO(1), /* FIFO */
        DT_CHR(2), /* character device */
        DT_DIR(4), /* directory */
        DT_BLK(6), /* block device */
        DT_REG(8), /* regular file */
        DT_LNK(10), /* symbolic link */
        DT_SOCK(12), /* socket */
        DT_WHT(14); /* whiteout */

        @JvmField
        val type: Byte = type.toByte()
    }

    class DirectoryEntry {
        @JvmField
        val type: DirentType
        @JvmField
        val name: String

        constructor(isFile: Boolean, name: String) : this(if (isFile) DirentType.DT_REG else DirentType.DT_DIR, name)

        constructor(type: DirentType, name: String) {
            this.type = type
            this.name = name
        }
    }

    private val path: String

    private val entries: MutableList<DirectoryEntry>

    constructor(oflags: Int, path: String, dir: File) : this(oflags, path, *createEntries(dir))

    constructor(oflags: Int, path: String, vararg entries: DirectoryEntry) : super(oflags) {
        this.path = path

        this.entries = ArrayList()
        this.entries.add(DirectoryEntry(false, "."))
        this.entries.add(DirectoryEntry(false, ".."))
        Collections.addAll(this.entries, *entries)
    }

    override fun getdents64(dirp: Pointer, size: Int): Int {
        var offset = 0
        val iterator = this.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val data = entry.name.toByteArray(StandardCharsets.UTF_8)
            val d_reclen = ARM.alignSize((data.size + 24).toLong(), 8L)

            if (offset + d_reclen >= size) {
                break
            }

            val dirent = Dirent(dirp.share(offset.toLong()))
            dirent.d_ino = 0L
            dirent.d_off = 0L
            dirent.d_reclen = d_reclen.toShort()
            dirent.d_type = entry.type.type
            dirent.d_name = Arrays.copyOf(data, data.size + 1)
            dirent.pack()
            offset += d_reclen.toInt()

            iterator.remove()
        }

        return offset
    }

    override fun close() {
    }

    override fun fstat(emulator: Emulator<*>, stat: StatStructure): Int {
        stat.st_mode = IO.S_IFDIR
        stat.st_dev = 0L
        stat.st_size = 0L
        stat.st_blksize = 0
        stat.st_ino = 0L
        stat.pack()
        return 0
    }

    override fun toString(): String {
        return path
    }

    override fun getPath(): String {
        return path
    }

    override fun statfs(statFS: StatFS): Int {
        statFS.setType(0xef53)
        statFS.setBlockSize(0x1000)
        statFS.f_blocks = 0x3235afL
        statFS.f_bfree = 0x2b5763L
        statFS.f_bavail = 0x2b5763L
        statFS.f_files = 0xcccb0L
        statFS.f_ffree = 0xcbd2eL
        statFS.f_fsid = intArrayOf(0xd3609fe8.toInt(), 0x4970d6b)
        statFS.setNameLen(0xff)
        statFS.setFrSize(0x1000)
        statFS.setFlags(0x426)
        statFS.pack()
        return 0
    }

    companion object {
        private fun createEntries(dir: File): Array<DirectoryEntry> {
            val list: MutableList<DirectoryEntry> = ArrayList()
            val files = dir.listFiles(VortexdbgFileFilter())
            if (files != null) {
                Arrays.sort(files)
                for (file in files) {
                    list.add(DirectoryEntry(file.isFile, file.name))
                }
            }
            return list.toTypedArray()
        }
    }
}
