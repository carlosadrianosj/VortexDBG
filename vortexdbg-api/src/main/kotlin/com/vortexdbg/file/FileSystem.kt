package com.vortexdbg.file

import java.io.File

interface FileSystem<T : NewFileIO> {

    fun getRootDir(): File
    fun createWorkDir(): File // Available only once rootDir is set; resolves to rootDir/vortexdbg_work

    fun open(pathname: String, oflags: Int): FileResult<T>?
    fun unlink(path: String)

    /**
     * @return `true` if the directory was created successfully
     */
    fun mkdir(path: String, mode: Int): Boolean

    fun rmdir(path: String)

    fun createSimpleFileIO(file: File, oflags: Int, path: String): T

    fun createDirectoryFileIO(file: File, oflags: Int, path: String): T

    fun rename(oldPath: String, newPath: String): Int

    companion object {
        const val DEFAULT_ROOT_FS = "rootfs/default"
        const val DEFAULT_WORK_DIR = "vortexdbg_work"
    }
}
