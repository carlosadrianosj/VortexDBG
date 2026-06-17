package com.vortexdbg.linux.android

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.Svc
import com.vortexdbg.Symbol
import com.vortexdbg.arm.Arm64Svc
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.linux.LinuxModule
import com.vortexdbg.linux.struct.dl_phdr_info64
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.MemoryBlock
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.pointer.VortexdbgStructure
import com.vortexdbg.spi.Dlfcn
import com.vortexdbg.spi.InitFunction
import com.vortexdbg.unix.struct.DlInfo64
import com.sun.jna.Pointer
import keystone.Keystone
import keystone.KeystoneArchitecture
import keystone.KeystoneEncoded
import keystone.KeystoneMode
import net.fornwall.jelf.ElfDynamicStructure
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections

open class ArmLD64 internal constructor(private val backend: Backend, svcMemory: SvcMemory) : Dlfcn(svcMemory) {

    override fun hook(svcMemory: SvcMemory, libraryName: String, symbolName: String, old: Long): Long {
        if ("libdl.so" == libraryName) {
            if (log.isDebugEnabled) {
                log.debug("link {}, old=0x{}", symbolName, java.lang.Long.toHexString(old))
            }
            when (symbolName) {
                "dl_iterate_phdr" ->
                    return svcMemory.registerSvc(object : Arm64Svc() {
                        private var block: MemoryBlock? = null
                        override fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer {
                            Keystone(KeystoneArchitecture.Arm64, KeystoneMode.LittleEndian).use { keystone ->
                                val encoded: KeystoneEncoded = keystone.assemble(Arrays.asList(
                                        "sub sp, sp, #0x10",
                                        "stp x29, x30, [sp]",
                                        "svc #0x" + Integer.toHexString(svcNumber),

                                        "ldr x13, [sp]",
                                        "add sp, sp, #0x8",
                                        "cmp x13, #0",
                                        "b.eq #0x58",
                                        "ldr x0, [sp]",
                                        "add sp, sp, #0x8",
                                        "ldr x1, [sp]",
                                        "add sp, sp, #0x8",
                                        "ldr x2, [sp]",
                                        "add sp, sp, #0x8",
                                        "blr x13",
                                        "cmp w0, #0",
                                        "b.eq #0xc",

                                        "ldr x13, [sp]",
                                        "add sp, sp, #0x8",
                                        "cmp x13, #0",
                                        "b.eq #0x58",
                                        "add sp, sp, #0x18",
                                        "b 0x40",

                                        "mov x8, #0",
                                        "mov x12, #0x" + Integer.toHexString(svcNumber),
                                        "mov x16, #0x" + Integer.toHexString(Svc.POST_CALLBACK_SYSCALL_NUMBER),
                                        "svc #0",

                                        "ldp x29, x30, [sp]",
                                        "add sp, sp, #0x10",
                                        "ret"))
                                val code = encoded.machineCode
                                val pointer = svcMemory.allocate(code.size, "dl_iterate_phdr")
                                pointer.write(0L, code, 0, code.size)
                                if (log.isDebugEnabled) {
                                    log.debug("dl_iterate_phdr: pointer={}", pointer)
                                }
                                return pointer
                            }
                        }
                        override fun handle(emulator: Emulator<*>): Long {
                            if (block != null) {
                                throw IllegalStateException()
                            }

                            val context = emulator.getContext<RegisterContext>()
                            val cb = context.getPointerArg(0)
                            val data = context.getPointerArg(1)

                            val modules = emulator.getMemory().getLoadedModules()
                            val list = ArrayList<LinuxModule>()
                            for (module in modules) {
                                val lm = module as LinuxModule
                                if (lm.elfFile != null) {
                                    list.add(lm)
                                }
                            }
                            Collections.reverse(list)
                            val size = VortexdbgStructure.calculateSize(dl_phdr_info64::class.java)
                            block = emulator.getMemory().malloc(size * list.size, true)
                            var ptr = block!!.getPointer()
                            val backend = emulator.getBackend()
                            var sp = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_SP)
                            if (log.isDebugEnabled) {
                                log.debug("dl_iterate_phdr cb={}, data={}, size={}, sp={}", cb, data, list.size, sp)
                            }

                            try {
                                sp = sp.share(-8, 0)
                                sp.setLong(0L, 0) // NULL-terminated

                                for (module in list) {
                                    val info = dl_phdr_info64(ptr)
                                    val dlpi_addr = VortexdbgPointer.pointer(emulator, module.virtualBase)
                                    assert(dlpi_addr != null)
                                    info.dlpi_addr = dlpi_addr.peer
                                    val dynamicStructure: ElfDynamicStructure? = module.dynamicStructure
                                    if (dynamicStructure != null && dynamicStructure.soName > 0 && dynamicStructure.dt_strtab_offset > 0) {
                                        info.dlpi_name = VortexdbgPointer.nativeValueOf(dlpi_addr.share(dynamicStructure.dt_strtab_offset + dynamicStructure.soName))
                                    } else {
                                        info.dlpi_name = VortexdbgPointer.nativeValueOf(module.createPathMemory(svcMemory))
                                    }
                                    info.dlpi_phdr = VortexdbgPointer.nativeValueOf(dlpi_addr.share(module.elfFile!!.ph_offset))
                                    info.dlpi_phnum = module.elfFile!!.num_ph
                                    info.pack()

                                    sp = sp.share(-8, 0)
                                    sp.setPointer(0L, data) // data

                                    sp = sp.share(-8, 0)
                                    sp.setLong(0L, size.toLong()) // size

                                    sp = sp.share(-8, 0)
                                    sp.setPointer(0L, ptr) // dl_phdr_info

                                    sp = sp.share(-8, 0)
                                    sp.setPointer(0L, cb) // callback

                                    ptr = ptr.share(size.toLong(), 0)
                                }

                                return context.getLongArg(0)
                            } finally {
                                backend.reg_write(Arm64Const.UC_ARM64_REG_SP, sp.peer)
                            }
                        }
                        override fun handlePostCallback(emulator: Emulator<*>) {
                            super.handlePostCallback(emulator)

                            if (block == null) {
                                throw IllegalStateException()
                            }
                            block!!.free()
                            block = null
                        }
                    }).peer
                "dlerror" ->
                    return svcMemory.registerSvc(object : Arm64Svc() {
                        override fun handle(emulator: Emulator<*>): Long {
                            return error.peer
                        }
                    }).peer
                "dlclose" ->
                    return svcMemory.registerSvc(object : Arm64Svc() {
                        override fun handle(emulator: Emulator<*>): Long {
                            val context = emulator.getContext<RegisterContext>()
                            val handle = context.getLongArg(0)
                            if (log.isDebugEnabled) {
                                log.debug("dlclose handle=0x{}", java.lang.Long.toHexString(handle))
                            }
                            return dlclose(emulator.getMemory(), handle).toLong()
                        }
                    }).peer
                "dlopen" ->
                    return svcMemory.registerSvc(object : Arm64Svc() {
                        override fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer {
                            val buffer = ByteBuffer.allocate(56)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.putInt(0xd10043ff.toInt()) // "sub sp, sp, #0x10"
                            buffer.putInt(0xa9007bfd.toInt()) // "stp x29, x30, [sp]"
                            buffer.putInt(Arm64Svc.assembleSvc(svcNumber)) // "svc #0x" + Integer.toHexString(svcNumber)
                            buffer.putInt(0xf94003ed.toInt()) // "ldr x13, [sp]"
                            buffer.putInt(0x910023ff.toInt()) // "add sp, sp, #0x8", manipulated stack in dlopen
                            buffer.putInt(0xf10001bf.toInt()) // "cmp x13, #0"
                            buffer.putInt(0x54000060) // "b.eq #0x24"
                            buffer.putInt(0x10ffff9e) // "adr lr, #-0xf", jump to ldr x13, [sp]
                            buffer.putInt(0xd61f01a0.toInt()) // "br x13", call init array
                            buffer.putInt(0xf94003e0.toInt()) // "ldr x0, [sp]", with return address
                            buffer.putInt(0x910023ff.toInt()) // "add sp, sp, #0x8"
                            buffer.putInt(0xa9407bfd.toInt()) // "ldp x29, x30, [sp]"
                            buffer.putInt(0x910043ff.toInt()) // "add sp, sp, #0x10"
                            buffer.putInt(0xd65f03c0.toInt()) // "ret"
                            val code = buffer.array()
                            val pointer = svcMemory.allocate(code.size, "dlopen")
                            pointer.write(0L, code, 0, code.size)
                            return pointer
                        }
                        override fun handle(emulator: Emulator<*>): Long {
                            val context = emulator.getContext<RegisterContext>()
                            val filename = context.getPointerArg(0)
                            val flags = context.getIntArg(1)
                            if (log.isDebugEnabled) {
                                log.debug("dlopen filename={}, flags={}, LR={}", filename!!.getString(0L), flags, context.getLRPointer())
                            }
                            return dlopen(emulator.getMemory(), filename!!.getString(0L), emulator)
                        }
                    }).peer
                "dladdr" ->
                    return svcMemory.registerSvc(object : Arm64Svc() {
                        override fun handle(emulator: Emulator<*>): Long {
                            val context = emulator.getContext<RegisterContext>()
                            val addr = context.getLongArg(0)
                            val info = context.getPointerArg(1)
                            if (log.isDebugEnabled) {
                                log.debug("dladdr addr=0x{}, info={}, LR={}", java.lang.Long.toHexString(addr), info, context.getLRPointer())
                            }
                            val module = emulator.getMemory().findModuleByAddress(addr)
                                ?: return 0

                            val symbol = module.findClosestSymbolByAddress(addr, true)

                            val dlInfo = DlInfo64(info)
                            dlInfo.dli_fname = VortexdbgPointer.nativeValueOf(module.createPathMemory(svcMemory))
                            dlInfo.dli_fbase = module.base
                            if (symbol != null) {
                                dlInfo.dli_sname = VortexdbgPointer.nativeValueOf(symbol.createNameMemory(svcMemory))
                                dlInfo.dli_saddr = symbol.getAddress()
                            }
                            dlInfo.pack()
                            return 1
                        }
                    }).peer
                "dlsym" ->
                    return svcMemory.registerSvc(object : Arm64Svc() {
                        override fun handle(emulator: Emulator<*>): Long {
                            val context = emulator.getContext<RegisterContext>()
                            val handle = context.getLongArg(0)
                            val symbol = context.getPointerArg(1)
                            if (log.isDebugEnabled) {
                                log.debug("dlsym handle=0x{}, symbol={}, LR={}", java.lang.Long.toHexString(handle), symbol!!.getString(0L), context.getLRPointer())
                            }
                            return dlsym(emulator, handle, symbol!!.getString(0L))
                        }
                    }).peer
                "dl_unwind_find_exidx" ->
                    return svcMemory.registerSvc(object : Arm64Svc() {
                        override fun handle(emulator: Emulator<*>): Long {
                            val context = emulator.getContext<RegisterContext>()
                            val pc = context.getPointerArg(0)
                            val pcount = context.getPointerArg(1)
                            log.info("dl_unwind_find_exidx pc{}, pcount={}", pc, pcount)
                            return 0
                        }
                    }).peer
            }
        }
        return 0
    }

    private fun dlopen(memory: Memory, filename: String, emulator: Emulator<*>): Long {
        var pointer = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_SP)
        try {
            val module = memory.dlopen(filename, false)
            pointer = pointer.share(-8, 0) // return value
            if (module == null) {
                pointer.setLong(0L, 0)

                pointer = pointer.share(-8, 0) // NULL-terminated
                pointer.setLong(0L, 0)

                if ("libnetd_client.so" != filename) {
                    log.info("dlopen failed: {}", filename)
                } else if (log.isDebugEnabled) {
                    log.debug("dlopen failed: {}", filename)
                }
                this.error.setString(0, "Resolve library $filename failed")
                return 0
            } else {
                pointer.setLong(0L, module.base)

                pointer = pointer.share(-8, 0) // NULL-terminated
                pointer.setLong(0L, 0)

                val m = module as LinuxModule
                if (m.getUnresolvedSymbol().isEmpty()) {
                    for (initFunction in m.initFunctionList) {
                        val address = initFunction.getAddress()
                        if (address == 0L) {
                            continue
                        }
                        if (log.isDebugEnabled) {
                            log.debug("[{}]PushInitFunction: 0x{}", m.name, java.lang.Long.toHexString(address))
                        }
                        pointer = pointer.share(-8, 0) // init array
                        pointer.setLong(0L, address)
                    }
                    m.initFunctionList.clear()
                }

                return module.base
            }
        } finally {
            backend.reg_write(Arm64Const.UC_ARM64_REG_SP, pointer.peer)
        }
    }

    private fun dlclose(memory: Memory, handle: Long): Int {
        if (memory.dlclose(handle)) {
            return 0
        } else {
            this.error.setString(0, "dlclose 0x" + java.lang.Long.toHexString(handle) + " failed")
            return -1
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ArmLD64::class.java)
    }

}
