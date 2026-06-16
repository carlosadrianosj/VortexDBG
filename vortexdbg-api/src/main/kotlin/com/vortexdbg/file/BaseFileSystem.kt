package com.vortexdbg.file

import com.vortexdbg.arm.context.RegisterContext

import com.vortexdbg.Emulator
import com.vortexdbg.unix.IO
import com.vortexdbg.unix.UnixEmulator
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

abstract class BaseFileSystem<T : NewFileIO>(
    @JvmField protected val emulator: Emulator<T>,
    @JvmField protected val rootDir: File
) : FileSystem<T> {

    init {
        try {
            initialize(this.rootDir)
        } catch (e: IOException) {
            throw IllegalStateException("initialize file system failed", e)
        }
    }

    @Throws(IOException::class)
    protected open fun initialize(rootDir: File) {
        FileUtils.forceMkdir(File(rootDir, "tmp"))
    }

    override fun open(pathname: String, oflags: Int): FileResult<T>? {
        if ("" == pathname) {
            return FileResult.failed(UnixEmulator.ENOENT) // No such file or directory
        }

        if (IO.STDIN == pathname) {
            return FileResult.success(createStdin(oflags))
        }

        if (IO.STDOUT == pathname || IO.STDERR == pathname) {
            try {
                val stdio = File(rootDir, "$pathname.txt")
                if (!stdio.exists() && !stdio.createNewFile()) {
                    throw IOException("create new file failed: $stdio")
                }
                return FileResult.success(createStdout(oflags, stdio, pathname))
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        }

        val file = File(rootDir, pathname)
        return createFileIO(file, oflags, pathname)
    }

    protected abstract fun createStdout(oflags: Int, stdio: File, pathname: String): T

    protected abstract fun createStdin(oflags: Int): T

    private fun createFileIO(file: File, oflags: Int, path: String): FileResult<T> {
        val directory = hasDirectory(oflags)
        if (file.isFile && directory) {
            return FileResult.failed(UnixEmulator.ENOTDIR)
        }

        val create = hasCreat(oflags)
        if (file.exists()) {
            if (create && hasExcl(oflags)) {
                return FileResult.failed(UnixEmulator.EEXIST)
            }
            return FileResult.success(if (file.isDirectory) createDirectoryFileIO(file, oflags, path) else createSimpleFileIO(file, oflags, path))
        }

        if (!create) {
            return FileResult.failed(UnixEmulator.ENOENT)
        }

        try {
            if (directory) {
                FileUtils.forceMkdir(file)
                return FileResult.success(createDirectoryFileIO(file, oflags, path))
            } else {
                if (!file.parentFile.exists()) {
                    FileUtils.forceMkdir(file.parentFile)
                }
                FileUtils.touch(file)
                return FileResult.success(createSimpleFileIO(file, oflags, path))
            }
        } catch (e: IOException) {
            throw IllegalStateException("createNewFile failed: $file", e)
        }
    }

    override fun mkdir(path: String, mode: Int): Boolean {
        val dir = File(rootDir, path)
        if (emulator.getSyscallHandler().isVerbose()) {
            System.out.printf("mkdir '%s' with mode 0x%x from %s%n", path, mode, emulator.getContext<RegisterContext>().getLRPointer())
        }

        return if (dir.exists()) {
            true
        } else {
            dir.mkdirs()
        }
    }

    override fun rmdir(path: String) {
        val dir = File(rootDir, path)
        FileUtils.deleteQuietly(dir)

        if (emulator.getSyscallHandler().isVerbose()) {
            System.out.printf("rmdir '%s' from %s%n", path, emulator.getContext<RegisterContext>().getLRPointer())
        }
    }

    protected abstract fun hasCreat(oflags: Int): Boolean
    protected abstract fun hasDirectory(oflags: Int): Boolean
    protected abstract fun hasAppend(oflags: Int): Boolean
    protected abstract fun hasExcl(oflags: Int): Boolean

    override fun unlink(path: String) {
        val file = File(rootDir, path)
        FileUtils.deleteQuietly(file)
        if (log.isDebugEnabled) {
            log.debug("unlink path={}, file={}", path, file)
        }
        if (emulator.getSyscallHandler().isVerbose()) {
            System.out.printf("unlink '%s' from %s%n", path, emulator.getContext<RegisterContext>().getLRPointer())
        }
    }

    override fun getRootDir(): File {
        return rootDir
    }

    override fun createWorkDir(): File {
        val workDir = File(rootDir, FileSystem.DEFAULT_WORK_DIR)
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw IllegalStateException("mkdirs failed: $workDir")
        }
        return workDir
    }

    override fun rename(oldPath: String, newPath: String): Int {
        val oldFile = File(rootDir, oldPath)
        val newFile = File(rootDir, newPath)

        try {
            FileUtils.forceMkdir(newFile.parentFile)

            if (oldFile.exists()) {
                Files.move(oldFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            if (emulator.getSyscallHandler().isVerbose()) {
                System.out.printf("rename '%s' to '%s' from %s%n", oldPath, newPath, emulator.getContext<RegisterContext>().getLRPointer())
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
        return 0
    }

    companion object {
        private val log = LoggerFactory.getLogger(BaseFileSystem::class.java)
    }
}
