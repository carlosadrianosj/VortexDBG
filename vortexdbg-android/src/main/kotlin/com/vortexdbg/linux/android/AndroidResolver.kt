package com.vortexdbg.linux.android

import com.vortexdbg.AndroidEmulator
import com.vortexdbg.Emulator
import com.vortexdbg.LibraryResolver
import com.vortexdbg.file.FileResult
import com.vortexdbg.file.FileSystem
import com.vortexdbg.file.IOResolver
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.hook.InlineHook
import com.vortexdbg.linux.file.ByteArrayFileIO
import com.vortexdbg.linux.file.DirectoryFileIO
import com.vortexdbg.linux.file.LogCatFileIO
import com.vortexdbg.linux.file.SimpleFileIO
import com.vortexdbg.linux.thread.ThreadJoin19
import com.vortexdbg.linux.thread.ThreadJoin23
import com.vortexdbg.spi.LibraryFile
import com.vortexdbg.spi.SyscallHandler
import com.vortexdbg.unix.ThreadJoinVisitor
import com.vortexdbg.utils.ResourceUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.IOUtils

import java.io.File
import java.io.IOException
import java.net.JarURLConnection
import java.net.URL
import java.util.jar.JarEntry

open class AndroidResolver(private val sdk: Int, vararg needed: String) : LibraryResolver, IOResolver<AndroidFileIO> {

    private val needed: List<String> = needed.toList()

    fun patchThread(emulator: Emulator<*>, inlineHook: InlineHook, visitor: ThreadJoinVisitor) {
        when (sdk) {
            19 -> ThreadJoin19.patch(emulator, inlineHook, visitor)
            23 -> ThreadJoin23.patch(emulator, inlineHook, visitor)
            else -> throw UnsupportedOperationException()
        }
    }

    fun getSdk(): Int {
        return sdk
    }

    override fun resolveLibrary(emulator: Emulator<*>, libraryName: String): LibraryFile? {
        if (!needed.isEmpty() && !needed.contains(libraryName)) {
            return null
        }

        return resolveLibrary(emulator, libraryName, sdk, javaClass)
    }

    override fun resolve(emulator: Emulator<AndroidFileIO>, path: String, oflags: Int): FileResult<AndroidFileIO>? {
        val fileSystem: FileSystem<AndroidFileIO> = emulator.getFileSystem()
        val rootDir = fileSystem.getRootDir()
        if (path.startsWith(LogCatFileIO.LOG_PATH_PREFIX)) {
            try {
                val log = File(rootDir, path)
                val logDir = log.parentFile
                if (!logDir.exists() && !logDir.mkdirs()) {
                    throw IOException("mkdirs failed: $logDir")
                }
                if (!log.exists() && !log.createNewFile()) {
                    throw IOException("create new file failed: $log")
                }
                return FileResult.success<AndroidFileIO>(LogCatFileIO(emulator, oflags, log, path))
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        }

        if ("." == path) {
            return FileResult.success(createFileIO(fileSystem.createWorkDir(), path, oflags))
        }

        val androidResource = FilenameUtils.normalize("/android/sdk$sdk/$path", true)
        val url = javaClass.getResource(androidResource)
        if (url != null) {
            return FileResult.fallback(createFileIO(url, path, oflags))
        }

        return null
    }

    private fun createFileIO(url: URL, pathname: String, oflags: Int): AndroidFileIO? {
        val file = ResourceUtils.toFile(url)
        if (file != null) {
            return createFileIO(file, pathname, oflags)
        }

        try {
            val connection = url.openConnection()
            connection.getInputStream().use { inputStream ->
                if (connection is JarURLConnection) {
                    val jarFile = connection.jarFile
                    val entry = connection.jarEntry
                    if (entry.isDirectory) {
                        val entryEnumeration = jarFile.entries()
                        val list = ArrayList<DirectoryFileIO.DirectoryEntry>()
                        while (entryEnumeration.hasMoreElements()) {
                            val check = entryEnumeration.nextElement()
                            if (entry.name == check.name) {
                                continue
                            }
                            if (check.name.startsWith(entry.name)) {
                                val isDir = check.isDirectory
                                var sub = check.name.substring(entry.name.length)
                                if (isDir) {
                                    sub = sub.substring(0, sub.length - 1)
                                }
                                if (!sub.contains("/")) {
                                    list.add(DirectoryFileIO.DirectoryEntry(true, sub))
                                }
                            }
                        }
                        return DirectoryFileIO(oflags, pathname, *list.toTypedArray())
                    } else {
                        val data = IOUtils.toByteArray(inputStream)
                        return ByteArrayFileIO(oflags, pathname, data)
                    }
                } else {
                    throw IllegalStateException(connection.javaClass.name)
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException(pathname, e)
        }
    }

    private fun createFileIO(file: File, pathname: String, oflags: Int): AndroidFileIO? {
        if (file.canRead()) {
            return if (file.isDirectory) DirectoryFileIO(oflags, pathname) else SimpleFileIO(oflags, file, pathname)
        }

        return null
    }

    override fun onSetToLoader(emulator: Emulator<*>) {
        val androidEmulator = emulator as AndroidEmulator
        val syscallHandler: SyscallHandler<AndroidFileIO> = androidEmulator.getSyscallHandler()
        syscallHandler.addIOResolver(this)
    }

    companion object {
        @JvmStatic
        internal fun resolveLibrary(emulator: Emulator<*>, libraryName: String, sdk: Int): LibraryFile? {
            return resolveLibrary(emulator, libraryName, sdk, AndroidResolver::class.java)
        }

        @JvmStatic
        protected fun resolveLibrary(emulator: Emulator<*>, libraryName: String, sdk: Int, resClass: Class<*>): LibraryFile? {
            val lib = if (emulator.is32Bit()) "lib" else "lib64"
            val name = "/android/sdk$sdk/$lib/" + libraryName.replace('+', 'p')
            val url = resClass.getResource(name)
            if (url != null) {
                return URLibraryFile(url, libraryName, sdk, emulator.is64Bit())
            }
            return null
        }
    }

}
