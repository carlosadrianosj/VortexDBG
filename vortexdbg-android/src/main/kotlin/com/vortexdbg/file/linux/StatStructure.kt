package com.vortexdbg.file.linux

import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

abstract class StatStructure(p: Pointer?) : VortexdbgStructure(p) {

    @JvmField
    var st_dev: Long = 0
    @JvmField
    var st_ino: Long = 0
    @JvmField
    var st_mode: Int = 0
    @JvmField
    var st_nlink: Int = 0
    @JvmField
    var st_uid: Int = 0
    @JvmField
    var st_gid: Int = 0
    @JvmField
    var st_rdev: Long = 0
    @JvmField
    var st_size: Long = 0
    @JvmField
    var st_blksize: Int = 0
    @JvmField
    var st_blocks: Long = 0

    /**
     * @param st_atim millis
     */
    abstract fun setSt_atim(st_atim: Long, tv_nsec: Long)

    /**
     * @param st_mtim millis
     */
    abstract fun setSt_mtim(st_mtim: Long, tv_nsec: Long)

    /**
     * @param st_ctim millis
     */
    abstract fun setSt_ctim(st_ctim: Long, tv_nsec: Long)

    /**
     * @param lastModified millis
     */
    fun setLastModification(lastModified: Long) {
        setLastModification(lastModified, 0L)
    }

    /**
     * @param lastModified millis
     */
    fun setLastModification(lastModified: Long, tv_nsec: Long) {
        setSt_atim(lastModified, tv_nsec)
        setSt_mtim(lastModified, tv_nsec)
        setSt_ctim(lastModified, tv_nsec)
    }

    open fun setSt_ino(st_ino: Long) {
        this.st_ino = st_ino
    }

}
