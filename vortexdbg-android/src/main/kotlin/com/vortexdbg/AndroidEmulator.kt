package com.vortexdbg

import com.vortexdbg.arm.ARMEmulator
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.linux.android.dvm.VM

import java.io.File

interface AndroidEmulator : ARMEmulator<AndroidFileIO> {

    fun createDalvikVM(): VM

    /**
     * @param apkFile 可为null
     */
    fun createDalvikVM(apkFile: File?): VM

    /**
     * jar as apk
     */
    fun createDalvikVM(callingClass: Class<*>): VM

    @Suppress("unused")
    fun getDalvikVM(): VM?

}
