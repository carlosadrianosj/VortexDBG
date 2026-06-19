package com.vortexdbg

import com.vortexdbg.arm.ARMEmulator
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.linux.android.dvm.VM

import java.io.File

interface AndroidEmulator : ARMEmulator<AndroidFileIO> {

    fun createDalvikVM(): VM

    /**
     * @param apkFile may be null
     */
    fun createDalvikVM(apkFile: File?): VM

    /** Treats the calling class's code source (a jar) as the apk. */
    fun createDalvikVM(callingClass: Class<*>): VM

    @Suppress("unused")
    fun getDalvikVM(): VM?

}
