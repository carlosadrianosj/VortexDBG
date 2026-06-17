package com.vortexdbg.linux.android.dvm

import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.apk.Apk
import com.vortexdbg.spi.LibraryFile

import java.nio.ByteBuffer

internal class ApkLibraryFile(
    private val baseVM: BaseVM,
    private val apk: Apk,
    private val soName: String,
    private val soData: ByteArray,
    private val packageName: String?,
    private val is64Bit: Boolean
) : LibraryFile {

    private val appDir: String = if (packageName == null) "" else ('/'.toString() + packageName + "-1")

    override fun getFileSize(): Long {
        return soData.size.toLong()
    }

    override fun getName(): String {
        return soName
    }

    override fun getMapRegionName(): String {
        return getPath()
    }

    override fun resolveLibrary(emulator: Emulator<*>, soName: String): LibraryFile? {
        val libData = baseVM.loadLibraryData(apk, soName)
        return if (libData == null) null else ApkLibraryFile(baseVM, this.apk, soName, libData, packageName, is64Bit)
    }

    override fun mapBuffer(): ByteBuffer {
        return ByteBuffer.wrap(soData)
    }

    override fun getPath(): String {
        return "/data/app" + appDir + "/lib/" + (if (is64Bit) "arm64/" else "arm/") + soName
    }
}
