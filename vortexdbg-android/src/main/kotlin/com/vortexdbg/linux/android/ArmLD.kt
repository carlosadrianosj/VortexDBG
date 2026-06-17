package com.vortexdbg.linux.android

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.Svc
import com.vortexdbg.Symbol
import com.vortexdbg.arm.ArmHook
import com.vortexdbg.arm.ArmSvc
import com.vortexdbg.arm.HookStatus
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.linux.LinuxModule
import com.vortexdbg.linux.struct.dl_phdr_info32
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.MemoryBlock
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.pointer.VortexdbgStructure
import com.vortexdbg.spi.Dlfcn
import com.vortexdbg.spi.InitFunction
import com.vortexdbg.unix.struct.DlInfo32
import com.sun.jna.Pointer
import keystone.Keystone
import keystone.KeystoneArchitecture
import keystone.KeystoneEncoded
import keystone.KeystoneMode
import net.fornwall.jelf.ElfDynamicStructure
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.ArmConst

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections

open class ArmLD internal constructor(private val backend: Backend, svcMemory: SvcMemory) : Dlfcn(svcMemory) {

    override fun hook(svcMemory: SvcMemory, libraryName: String, symbolName: String, old: Long): Long {
        if ("libdl.so" == libraryName) {
            if (log.isDebugEnabled) {
                log.debug("link {}, old=0x{}", symbolName, java.lang.Long.toHexString(old))
            }
            when (symbolName) {
                "dl_iterate_phdr" ->
                    return svcMemory.registerSvc(object : ArmSvc() {
                        private var block: MemoryBlock? = null
                        override fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer {
                            Keystone(KeystoneArchitecture.Arm, KeystoneMode.Arm).use { keystone ->
                                val encoded: KeystoneEncoded = keystone.assemble(Arrays.asList(
                                        "push {r4-r7, lr}",
                                        "svc #0x" + Integer.toHexString(svcNumber),
                                        "pop {r7}",
                                        "cmp r7, #0",
                                        "beq 0x34",
                                        "pop {r0-r2}",
                                        "blx r7",
                                        "cmp r0, #0",
                                        "beq 0x8",
                                        "pop {r7}",
                                        "cmp r7, #0",
                                        "popne {r4-r6}",
                                        "bne 0x24",
                                        "mov r7, #0",
                                        "mov r5, #0x" + Integer.toHexString(Svc.POST_CALLBACK_SYSCALL_NUMBER),
                                        "mov r4, #0x" + Integer.toHexString(svcNumber),
                                        "svc #0",
                                        "pop {r4-r7, pc}"))
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
                            val size = VortexdbgStructure.calculateSize(dl_phdr_info32::class.java)
                            block = emulator.getMemory().malloc(size * list.size, true)
                            var ptr = block!!.getPointer()
                            val backend = emulator.getBackend()
                            var sp = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_SP)
                            if (log.isDebugEnabled) {
                                log.debug("dl_iterate_phdr cb={}, data={}, size={}, sp={}", cb, data, list.size, sp)
                            }

                            try {
                                sp = sp.share(-4, 0)
                                sp.setInt(0L, 0) // NULL-terminated

                                for (module in list) {
                                    val info = dl_phdr_info32(ptr)
                                    val dlpi_addr = VortexdbgPointer.pointer(emulator, module.virtualBase)
                                    assert(dlpi_addr != null)
                                    info.dlpi_addr = dlpi_addr.toUIntPeer().toInt()
                                    val dynamicStructure: ElfDynamicStructure? = module.dynamicStructure
                                    if (dynamicStructure != null && dynamicStructure.soName > 0 && dynamicStructure.dt_strtab_offset > 0) {
                                        info.dlpi_name = VortexdbgPointer.nativeValueOf(dlpi_addr.share(dynamicStructure.dt_strtab_offset + dynamicStructure.soName)).toInt()
                                    } else {
                                        info.dlpi_name = VortexdbgPointer.nativeValueOf(module.createPathMemory(svcMemory)).toInt()
                                    }
                                    info.dlpi_phdr = VortexdbgPointer.nativeValueOf(dlpi_addr.share(module.elfFile!!.ph_offset)).toInt()
                                    info.dlpi_phnum = module.elfFile!!.num_ph
                                    info.pack()

                                    sp = sp.share(-4, 0)
                                    sp.setPointer(0L, data) // data

                                    sp = sp.share(-4, 0)
                                    sp.setInt(0L, size) // size

                                    sp = sp.share(-4, 0)
                                    sp.setPointer(0L, ptr) // dl_phdr_info

                                    sp = sp.share(-4, 0)
                                    sp.setPointer(0L, cb) // callback

                                    ptr = ptr.share(size.toLong(), 0)
                                }

                                return context.getLongArg(0)
                            } finally {
                                backend.reg_write(ArmConst.UC_ARM_REG_SP, sp.peer)
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
                    return svcMemory.registerSvc(object : ArmSvc() {
                        override fun handle(emulator: Emulator<*>): Long {
                            return error.peer
                        }
                    }).peer
                "dlclose" ->
                    return svcMemory.registerSvc(object : ArmSvc() {
                        override fun handle(emulator: Emulator<*>): Long {
                            val context = emulator.getContext<RegisterContext>()
                            val handle = context.getIntArg(0)
                            if (log.isDebugEnabled) {
                                log.debug("dlclose handle=0x{}", java.lang.Long.toHexString(handle.toLong()))
                            }
                            return dlclose(emulator.getMemory(), handle.toLong()).toLong()
                        }
                    }).peer
                "dlopen" ->
                    return svcMemory.registerSvc(object : ArmSvc() {
                        override fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer {
                            val buffer = ByteBuffer.allocate(28)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.putInt(0xe92d40f0.toInt()) // push {r4-r7, lr}
                            buffer.putInt(ArmSvc.assembleSvc(svcNumber)) // svc #svcNumber
                            buffer.putInt(0xe49d7004.toInt()) // pop {r7}
                            buffer.putInt(0xe3570000.toInt()) // cmp r7, #0
                            buffer.putInt(0x124fe010) // subne lr, pc, #16
                            buffer.putInt(0x112fff17) // bxne r7
                            buffer.putInt(0xe8bd80f1.toInt()) // pop {r0, r4-r7, pc} with return address
                            val code = buffer.array()
                            val pointer = svcMemory.allocate(code.size, "dlopen")
                            pointer.write(code)
                            return pointer
                        }
                        override fun handle(emulator: Emulator<*>): Long {
                            val context = emulator.getContext<RegisterContext>()
                            val fileNamePointer = context.getPointerArg(0)
                            val flags = context.getIntArg(1)

                            val filename: String
                            if (fileNamePointer == null) {
                                val module = emulator.getMemory().findModuleByAddress(context.getLR())
                                    ?: throw UnsupportedOperationException()
                                filename = module.name
                            } else {
                                filename = fileNamePointer.getString(0L)
                            }
                            if (log.isDebugEnabled) {
                                log.debug("dlopen filename={}, flags={}, LR={}", filename, flags, context.getLRPointer())
                            }
                            return dlopen(emulator.getMemory(), filename, emulator)
                        }
                    }).peer
                "dladdr" ->
                    return svcMemory.registerSvc(object : ArmSvc() {
                        override fun handle(emulator: Emulator<*>): Long {
                            val context = emulator.getContext<RegisterContext>()
                            val addr = context.getIntArg(0)
                            val info = context.getPointerArg(1)
                            if (log.isDebugEnabled) {
                                log.debug("dladdr addr=0x{}, info={}, LR={}", java.lang.Long.toHexString(addr.toLong()), info, context.getLRPointer())
                            }
                            val module = emulator.getMemory().findModuleByAddress(addr.toLong())
                                ?: return 0

                            val symbol = module.findClosestSymbolByAddress(addr.toLong(), true)

                            val dlInfo = DlInfo32(info)
                            dlInfo.dli_fname = VortexdbgPointer.nativeValueOf(module.createPathMemory(svcMemory)).toInt()
                            dlInfo.dli_fbase = module.base.toInt()
                            if (symbol != null) {
                                dlInfo.dli_sname = VortexdbgPointer.nativeValueOf(symbol.createNameMemory(svcMemory)).toInt()
                                dlInfo.dli_saddr = symbol.getAddress().toInt()
                            }
                            dlInfo.pack()
                            return 1
                        }
                    }).peer
                "dlsym" ->
                    return svcMemory.registerSvc(object : ArmSvc() {
                        override fun handle(emulator: Emulator<*>): Long {
                            val context = emulator.getContext<RegisterContext>()
                            val handle = context.getIntArg(0)
                            val symbol = context.getPointerArg(1)
                            if (log.isDebugEnabled) {
                                log.debug("dlsym handle=0x{}, symbol={}, LR={}", java.lang.Long.toHexString(handle.toLong()), symbol!!.getString(0L), context.getLRPointer())
                            }
                            return dlsym(emulator, (handle.toLong() and 0xffffffffL), symbol!!.getString(0L))
                        }
                    }).peer
                "dl_unwind_find_exidx" ->
                    return svcMemory.registerSvc(object : ArmSvc() {
                        override fun handle(emulator: Emulator<*>): Long {
                            val context = emulator.getContext<RegisterContext>()
                            val pc = context.getPointerArg(0)
                            val pcount = context.getPointerArg(1)
                            log.info("dl_unwind_find_exidx pc={}, pcount={}", pc, pcount)
                            return 0
                        }
                    }).peer
                "android_get_application_target_sdk_version" ->
                    return svcMemory.registerSvc(object : ArmHook() {
                        override fun hook(emulator: Emulator<*>): HookStatus {
                            return HookStatus.LR(emulator, 0)
                        }
                    }).peer
            }
        }
        return 0
    }

    private fun dlopen(memory: Memory, filename: String, emulator: Emulator<*>): Long {
        var pointer = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_SP)
        try {
            val module = memory.dlopen(filename, false)
            pointer = pointer.share(-4, 0) // return value
            if (module == null) {
                pointer.setInt(0L, 0)

                pointer = pointer.share(-4, 0) // NULL-terminated
                pointer.setInt(0L, 0)

                if ("libnetd_client.so" != filename) {
                    log.info("dlopen failed: {}, LR={}", filename, emulator.getContext<RegisterContext>().getLRPointer())
                } else if (log.isDebugEnabled) {
                    log.debug("dlopen failed: {}", filename)
                }
                this.error.setString(0L, "Resolve library $filename failed")
                return 0
            } else {
                pointer.setInt(0L, module.base.toInt())

                pointer = pointer.share(-4, 0) // NULL-terminated
                pointer.setInt(0L, 0)

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
                        pointer = pointer.share(-4, 0) // init array
                        pointer.setInt(0L, address.toInt())
                    }
                    m.initFunctionList.clear()
                }

                return module.base
            }
        } finally {
            backend.reg_write(ArmConst.UC_ARM_REG_SP, pointer.peer)
        }
    }

    private fun dlclose(memory: Memory, handle: Long): Int {
        if (memory.dlclose(handle)) {
            return 0
        } else {
            this.error.setString(0L, "dlclose 0x" + java.lang.Long.toHexString(handle) + " failed")
            return -1
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ArmLD::class.java)
    }

}
