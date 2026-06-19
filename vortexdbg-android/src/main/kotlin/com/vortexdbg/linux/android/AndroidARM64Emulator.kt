package com.vortexdbg.linux.android

import com.vortexdbg.AndroidEmulator
import com.vortexdbg.Family
import com.vortexdbg.arm.AbstractARM64Emulator
import com.vortexdbg.arm.backend.BackendFactory
import com.vortexdbg.file.FileSystem
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.file.linux.LinuxFileSystem
import com.vortexdbg.linux.ARM64SyscallHandler
import com.vortexdbg.linux.AndroidElfLoader
import com.vortexdbg.linux.android.dvm.DalvikVM64
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.spi.Dlfcn
import com.vortexdbg.spi.LibraryFile
import com.vortexdbg.unix.UnixSyscallHandler
import com.vortexdbg.unwind.Unwinder

import java.io.File
import java.net.URL

/** ARM64 (AArch64) Android emulator: wires up the loader, syscall handler, dyld and Dalvik VM. */
open class AndroidARM64Emulator internal constructor(
    processName: String?,
    rootDir: File?,
    backendFactories: Collection<BackendFactory>?
) : AbstractARM64Emulator<AndroidFileIO>(processName, rootDir, Family.Android64, backendFactories), AndroidEmulator {

    override fun createFileSystem(rootDir: File): FileSystem<AndroidFileIO> {
        return LinuxFileSystem(this, rootDir)
    }

    override fun createMemory(syscallHandler: UnixSyscallHandler<AndroidFileIO>, envs: Array<String>): Memory {
        return AndroidElfLoader(this, syscallHandler)
    }

    override fun createDyld(svcMemory: SvcMemory): Dlfcn {
        return ArmLD64(backend, svcMemory)
    }

    override fun createSyscallHandler(svcMemory: SvcMemory): UnixSyscallHandler<AndroidFileIO> {
        return ARM64SyscallHandler(svcMemory)
    }

    private fun createDalvikVMInternal(apkFile: File?): VM {
        return DalvikVM64(this, apkFile)
    }

    override fun createURLibraryFile(url: URL, libName: String): LibraryFile {
        return URLibraryFile(url, libName, -1, true)
    }

    override fun isPaddingArgument(): Boolean {
        return false
    }

    private var vm: VM? = null

    override fun createDalvikVM(): VM {
        return createDalvikVM(null as File?)
    }

    final override fun createDalvikVM(apkFile: File?): VM {
        if (vm != null) {
            throw IllegalStateException("vm is already created")
        }
        vm = createDalvikVMInternal(apkFile)
        return vm!!
    }

    override fun createDalvikVM(callingClass: Class<*>): VM {
        return createDalvikVM(File(callingClass.protectionDomain.codeSource.location.path))
    }

    final override fun getDalvikVM(): VM? {
        return vm
    }

    override fun getUnwinder(): Unwinder {
        return AndroidARM64Unwinder(this)
    }
}
