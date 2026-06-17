package com.vortexdbg.linux.unpack

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.UnHook
import com.vortexdbg.arm.backend.WriteHook
import com.vortexdbg.memory.MemRegion
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.spi.InitFunctionListener
import org.apache.commons.io.FileUtils
import unicorn.UnicornConst

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dump 针对 init_array 加密的 so 文件
 * <pre>
 * First before load so:
 *     memory.addModuleListener();
 * Then in onLoaded method:
 *     if ("libxxx.so".equals(module.name)) {
 *         File outFile = new File(FileUtils.getUserDirectory(), "Desktop/libxxx_patched.so");
 *         new ElfUnpacker(libxxxFileData, outFile).register(emulator, module);
 *     }
 * </pre>
 */
class ElfUnpacker(private val elfFile: ByteArray, private val outFile: File) {

    private val buffer: ByteBuffer
    private var dirty = false

    init {
        if (outFile.isDirectory) {
            throw IllegalStateException("isDirectory")
        }

        this.buffer = ByteBuffer.allocate(8)
        this.buffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    fun register(emulator: Emulator<*>, module: Module) {
        module.setInitFunctionListener(object : InitFunctionListener {
            override fun onPreCallInitFunction(module: Module, initFunction: Long, index: Int) {
                dirty = false
            }
            override fun onPostCallInitFunction(module: Module, initFunction: Long, index: Int) {
                try {
                    if (dirty) {
                        println("Unpack initFunction=" + VortexdbgPointer.pointer(emulator, module.base + initFunction))
                        FileUtils.writeByteArrayToFile(outFile, elfFile)
                    }
                } catch (e: IOException) {
                    throw IllegalStateException(e)
                }
            }
        })

        for (region in module.getRegions()) {
            if ((region.perms and UnicornConst.UC_PROT_WRITE) == 0 && (region.perms and UnicornConst.UC_PROT_EXEC) == UnicornConst.UC_PROT_EXEC) { // 只读代码段
                println("Begin unpack " + module.name + ": 0x" + java.lang.Long.toHexString(region.begin) + "-0x" + java.lang.Long.toHexString(region.end))
                emulator.getBackend().hook_add_new(object : WriteHook {
                    private var unHook: UnHook? = null
                    override fun hook(backend: Backend, address: Long, size: Int, value: Long, user: Any?) {
                        val offset = address - module.base
                        val fileOffset = module.virtualMemoryAddressToFileOffset(offset)
                        if (size < 1 || size > 8) {
                            throw IllegalStateException("size=$size")
                        }
                        if (fileOffset >= 0) {
                            buffer.clear()
                            buffer.putLong(value)
                            System.arraycopy(buffer.array(), 0, elfFile, fileOffset, size)
                            dirty = true
                        }
                    }
                    override fun onAttach(unHook: UnHook) {
                        this.unHook = unHook
                    }
                    override fun detach() {
                        this.unHook!!.unhook()
                    }
                }, region.begin, region.end, emulator)
            }
        }
    }

}
