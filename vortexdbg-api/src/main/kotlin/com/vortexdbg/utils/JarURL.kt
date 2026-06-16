package com.vortexdbg.utils

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.IOUtils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UnsupportedEncodingException
import java.net.URL
import java.net.URLDecoder
import java.util.ArrayList
import java.util.jar.JarEntry
import java.util.jar.JarFile

internal class JarURL private constructor(
    @JvmField val jar: File,
    @JvmField val name: String,
    private val cleanupList: MutableList<File>
) : AutoCloseable {

    override fun close() {
        for (file in cleanupList) {
            FileUtils.deleteQuietly(file)
        }
    }

    fun getJarEntry(): JarEntry {
        try {
            JarFile(jar).use { jarFile ->
                var foundEntry: JarEntry? = null
                val entries = jarFile.entries()
                while (entries.hasMoreElements()) {
                    val jarEntry = entries.nextElement()
                    val entryName = jarEntry.name
                    if (name == entryName || (name + "/") == entryName) {
                        foundEntry = jarEntry
                        break
                    }
                }
                if (foundEntry == null) {
                    throw IllegalStateException("find failed: jar=" + jar + ", name=" + name)
                }
                return foundEntry
            }
        } catch (e: IOException) {
            throw IllegalStateException("jar=" + jar, e)
        }
    }

    companion object {
        @JvmStatic
        fun create(url: URL): JarURL {
            val path = url.path
            var index = path.indexOf("!")
            if (index == -1) {
                throw IllegalStateException(path)
            }
            val jarPath = path.substring(5, index)
            var name = path.substring(index + 2)
            var jar: File
            try {
                jar = File(URLDecoder.decode(jarPath, "UTF-8"))
            } catch (e: UnsupportedEncodingException) {
                throw IllegalStateException("jarPath=" + jarPath)
            }

            val cleanupList: MutableList<File> = ArrayList()
            while (name.indexOf("!").also { index = it } != -1) {
                val jarEntryName = name.substring(0, index)
                name = name.substring(index + 2)

                try {
                    JarFile(jar).use { jarFile ->
                        var foundEntry: JarEntry? = null
                        val entries = jarFile.entries()
                        while (entries.hasMoreElements()) {
                            val jarEntry = entries.nextElement()
                            val entryName = jarEntry.name
                            if (jarEntryName == entryName) {
                                foundEntry = jarEntry
                                break
                            }
                        }
                        if (foundEntry == null || foundEntry.isDirectory) {
                            throw IllegalStateException("find failed: jar=" + jar + ", jarEntryName=" + jarEntryName + ", name=" + name + ", foundEntry=" + foundEntry)
                        }

                        jar = File.createTempFile(FilenameUtils.getName(jarEntryName), "")
                        jarFile.getInputStream(foundEntry).use { inputStream ->
                            FileOutputStream(jar).use { outputStream ->
                                IOUtils.copy(inputStream as InputStream, outputStream as OutputStream)
                            }
                        }
                        cleanupList.add(jar)
                    }
                } catch (e: IOException) {
                    throw IllegalStateException(url.toString(), e)
                }
            }

            return JarURL(jar, name, cleanupList)
        }
    }
}
