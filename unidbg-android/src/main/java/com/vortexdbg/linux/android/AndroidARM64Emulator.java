package com.vortexdbg.linux.android;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.Family;
import com.vortexdbg.arm.AbstractARM64Emulator;
import com.vortexdbg.arm.backend.BackendFactory;
import com.vortexdbg.file.FileSystem;
import com.vortexdbg.file.linux.AndroidFileIO;
import com.vortexdbg.file.linux.LinuxFileSystem;
import com.vortexdbg.linux.ARM64SyscallHandler;
import com.vortexdbg.linux.AndroidElfLoader;
import com.vortexdbg.linux.android.dvm.DalvikVM64;
import com.vortexdbg.linux.android.dvm.VM;
import com.vortexdbg.memory.Memory;
import com.vortexdbg.memory.SvcMemory;
import com.vortexdbg.spi.Dlfcn;
import com.vortexdbg.spi.LibraryFile;
import com.vortexdbg.unix.UnixSyscallHandler;
import com.vortexdbg.unwind.Unwinder;

import java.io.File;
import java.net.URL;
import java.util.Collection;

/**
 * android arm emulator
 * Created by zhkl0228 on 2017/5/2.
 */

public class AndroidARM64Emulator extends AbstractARM64Emulator<AndroidFileIO> implements AndroidEmulator {

    protected AndroidARM64Emulator(String processName, File rootDir, Collection<BackendFactory> backendFactories) {
        super(processName, rootDir, Family.Android64, backendFactories);
    }

    @Override
    protected FileSystem<AndroidFileIO> createFileSystem(File rootDir) {
        return new LinuxFileSystem(this, rootDir);
    }

    @Override
    protected Memory createMemory(UnixSyscallHandler<AndroidFileIO> syscallHandler, String[] envs) {
        return new AndroidElfLoader(this, syscallHandler);
    }

    @Override
    protected Dlfcn createDyld(SvcMemory svcMemory) {
        return new ArmLD64(backend, svcMemory);
    }

    @Override
    protected UnixSyscallHandler<AndroidFileIO> createSyscallHandler(SvcMemory svcMemory) {
        return new ARM64SyscallHandler(svcMemory);
    }

    private VM createDalvikVMInternal(File apkFile) {
        return new DalvikVM64(this, apkFile);
    }

    @Override
    public LibraryFile createURLibraryFile(URL url, String libName) {
        return new URLibraryFile(url, libName, -1, true);
    }

    @Override
    protected boolean isPaddingArgument() {
        return false;
    }

    private VM vm;

    @Override
    public VM createDalvikVM() {
        return createDalvikVM((File) null);
    }

    @Override
    public final VM createDalvikVM(File apkFile) {
        if (vm != null) {
            throw new IllegalStateException("vm is already created");
        }
        vm = createDalvikVMInternal(apkFile);
        return vm;
    }

    @Override
    public VM createDalvikVM(Class<?> callingClass) {
        return createDalvikVM(new File(callingClass.getProtectionDomain().getCodeSource().getLocation().getPath()));
    }

    @Override
    public final VM getDalvikVM() {
        return vm;
    }

    @Override
    public Unwinder getUnwinder() {
        return new AndroidARM64Unwinder(this);
    }
}
