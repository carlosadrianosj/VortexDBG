package com.vortexdbg.ios;

import com.vortexdbg.EmulatorBuilder;
import com.vortexdbg.arm.ARMEmulator;
import com.vortexdbg.file.ios.DarwinFileIO;
import com.vortexdbg.ios.ipa.IpaLoader;

import java.util.ArrayList;
import java.util.List;

public class DarwinEmulatorBuilder extends EmulatorBuilder<ARMEmulator<DarwinFileIO>> {

    public static DarwinEmulatorBuilder for32Bit() {
        return new DarwinEmulatorBuilder(false);
    }

    public static DarwinEmulatorBuilder for64Bit() {
        return new DarwinEmulatorBuilder(true);
    }

    protected DarwinEmulatorBuilder(boolean is64Bit) {
        super(is64Bit);

        IpaLoader.addEnv(envList);
    }

    protected final List<String> envList = new ArrayList<>();

    public DarwinEmulatorBuilder addEnv(String env) {
        envList.add(env);
        return this;
    }

    @Override
    public ARMEmulator<DarwinFileIO> build() {
        return is64Bit ?
                new DarwinARM64Emulator(processName, rootDir, backendFactories, envList.toArray(new String[0])) :
                new DarwinARMEmulator(processName, rootDir, backendFactories, envList.toArray(new String[0]));
    }

}
