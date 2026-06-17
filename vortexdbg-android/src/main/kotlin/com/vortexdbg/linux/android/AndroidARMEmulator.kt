package com.vortexdbg.linux.android

import com.vortexdbg.AndroidEmulator
import com.vortexdbg.Family
import com.vortexdbg.arm.AbstractARMEmulator
import com.vortexdbg.arm.backend.BackendFactory
import com.vortexdbg.file.FileSystem
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.file.linux.LinuxFileSystem
import com.vortexdbg.linux.ARM32SyscallHandler
import com.vortexdbg.linux.AndroidElfLoader
import com.vortexdbg.linux.android.dvm.DalvikVM
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.spi.Dlfcn
import com.vortexdbg.spi.LibraryFile
import com.vortexdbg.unix.UnixSyscallHandler
import com.vortexdbg.unwind.Unwinder

import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * android arm emulator
 * Created by zhkl0228 on 2017/5/2.
 */

open class AndroidARMEmulator internal constructor(
    processName: String?,
    rootDir: File?,
    backendFactories: Collection<BackendFactory>?
) : AbstractARMEmulator<AndroidFileIO>(processName, rootDir, Family.Android32, backendFactories), AndroidEmulator {

    override fun createFileSystem(rootDir: File): FileSystem<AndroidFileIO> {
        return LinuxFileSystem(this, rootDir)
    }

    override fun createMemory(syscallHandler: UnixSyscallHandler<AndroidFileIO>, envs: Array<String>): Memory {
        return AndroidElfLoader(this, syscallHandler)
    }

    override fun createDyld(svcMemory: SvcMemory): Dlfcn {
        return ArmLD(backend, svcMemory)
    }

    override fun createSyscallHandler(svcMemory: SvcMemory): UnixSyscallHandler<AndroidFileIO> {
        return ARM32SyscallHandler(svcMemory)
    }

    private fun createDalvikVMInternal(apkFile: File?): VM {
        return DalvikVM(this, apkFile)
    }

    /**
     * https://github.com/lunixbochs/usercorn/blob/master/go/arch/arm/linux.go
     */
    override fun setupTraps() {
        super.setupTraps()

        val __kuser_memory_barrier = 0xe12fff1e.toInt() // bx lr
        memory.pointer(0xffff0fa0L).setInt(0L, __kuser_memory_barrier)

        val buffer = ByteBuffer.allocate(32)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0xf57ff05f.toInt()) // dmb sy
        buffer.putInt(0xe1923f9f.toInt()) // ldrex r3, [r2]
        buffer.putInt(0xe0533000.toInt()) // subs r3, r3, r0
        buffer.putInt(0x01823f91) // strexeq r3, r1, [r2]
        buffer.putInt(0x03330001) // teqeq r3, #1
        buffer.putInt(0x0afffffa) // beq #0xffff0fc4
        buffer.putInt(0xe2730000.toInt()) // rsbs r0, r3, #0
        buffer.putInt(0xeaffffef.toInt()) // b #0xffff0fa0
        val __kuser_cmpxchg = buffer.array()
        memory.pointer(0xffff0fc0L).write(__kuser_cmpxchg)
    }

    override fun createURLibraryFile(url: URL, libName: String): LibraryFile {
        return URLibraryFile(url, libName, -1, false)
    }

    override fun isPaddingArgument(): Boolean {
        return true
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
        return AndroidARMUnwinder(this)
    }

}
