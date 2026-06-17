package com.vortexdbg.file.linux

import com.vortexdbg.Emulator
import com.vortexdbg.file.BaseFileSystem
import com.vortexdbg.file.FileResult
import com.vortexdbg.file.FileSystem
import com.vortexdbg.linux.android.LogCatHandler
import com.vortexdbg.linux.file.DirectoryFileIO
import com.vortexdbg.linux.file.MapsFileIO
import com.vortexdbg.linux.file.NullFileIO
import com.vortexdbg.linux.file.SimpleFileIO
import com.vortexdbg.linux.file.Stdin
import com.vortexdbg.linux.file.Stdout
import com.vortexdbg.unix.IO
import org.apache.commons.io.FileUtils

import java.io.File
import java.io.IOException

open class LinuxFileSystem(emulator: Emulator<AndroidFileIO>, rootDir: File) :
    BaseFileSystem<AndroidFileIO>(emulator, rootDir), FileSystem<AndroidFileIO>, IOConstants {

    override fun open(pathname: String, oflags: Int): FileResult<AndroidFileIO>? {
        if ("/dev/tty" == pathname) {
            return FileResult.success<AndroidFileIO>(NullFileIO(pathname))
        }
        if ("/proc/self/maps" == pathname || ("/proc/" + emulator.getPid() + "/maps") == pathname ||
                ("/proc/self/task/" + emulator.getPid() + "/maps") == pathname) {
            return FileResult.success<AndroidFileIO>(MapsFileIO(emulator, oflags, pathname, emulator.getMemory().getLoadedModules()))
        }

        return super.open(pathname, oflags)
    }

    open fun getLogCatHandler(): LogCatHandler? {
        return null
    }

    @Throws(IOException::class)
    override fun initialize(rootDir: File) {
        super.initialize(rootDir)

        FileUtils.forceMkdir(File(rootDir, "system"))
        FileUtils.forceMkdir(File(rootDir, "data"))
    }

    override fun createSimpleFileIO(file: File, oflags: Int, path: String): AndroidFileIO {
        return SimpleFileIO(oflags, file, path)
    }

    override fun createDirectoryFileIO(file: File, oflags: Int, path: String): AndroidFileIO {
        return DirectoryFileIO(oflags, path, file)
    }

    override fun createStdin(oflags: Int): AndroidFileIO {
        return Stdin(oflags)
    }

    override fun createStdout(oflags: Int, stdio: File, pathname: String): AndroidFileIO {
        return Stdout(oflags, stdio, pathname, IO.STDERR == pathname, null)
    }

    override fun hasCreat(oflags: Int): Boolean {
        return (oflags and IOConstants.O_CREAT) != 0
    }

    override fun hasDirectory(oflags: Int): Boolean {
        return (oflags and IOConstants.O_DIRECTORY) != 0
    }

    override fun hasAppend(oflags: Int): Boolean {
        return (oflags and IOConstants.O_APPEND) != 0
    }

    override fun hasExcl(oflags: Int): Boolean {
        return (oflags and IOConstants.O_EXCL) != 0
    }
}
