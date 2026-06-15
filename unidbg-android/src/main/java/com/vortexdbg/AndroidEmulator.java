package com.vortexdbg;

import com.vortexdbg.arm.ARMEmulator;
import com.vortexdbg.file.linux.AndroidFileIO;
import com.vortexdbg.linux.android.dvm.VM;

import java.io.File;

public interface AndroidEmulator extends ARMEmulator<AndroidFileIO> {

    VM createDalvikVM();

    /**
     * @param apkFile 可为null
     */
    VM createDalvikVM(File apkFile);

    /**
     * jar as apk
     */
    VM createDalvikVM(Class<?> callingClass);

    @SuppressWarnings("unused")
    VM getDalvikVM();

}
