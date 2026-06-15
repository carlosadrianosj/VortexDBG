package com.vortexdbg.ios.ipa;

import com.vortexdbg.Emulator;
import com.vortexdbg.file.ios.DarwinFileIO;
import com.vortexdbg.ios.MachOModule;

import java.io.File;

public interface EmulatorConfigurator {

    void configure(Emulator<DarwinFileIO> emulator, String executableBundlePath, File rootDir, String bundleIdentifier);

    void onExecutableLoaded(Emulator<DarwinFileIO> emulator, MachOModule executable);

}
