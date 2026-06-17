package com.vortexdbg.linux.android.dvm.apk

import java.io.File

object ApkFactory {

    @JvmStatic
    fun createApk(file: File): Apk {
        return if (file.isDirectory) ApkDir(file) else ApkFile(file)
    }

}
