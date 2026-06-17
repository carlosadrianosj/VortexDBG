package com.vortexdbg.arm

import capstone.api.Disassembler
import capstone.api.DisassemblerFactory
import capstone.api.Instruction
import com.alibaba.fastjson.util.IOUtils
import com.vortexdbg.AbstractEmulator
import com.vortexdbg.Family
import com.vortexdbg.Module
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.BackendFactory
import com.vortexdbg.arm.backend.EventMemHook
import com.vortexdbg.arm.backend.UnHook
import com.vortexdbg.arm.context.BackendArm64RegisterContext
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.debugger.Debugger
import com.vortexdbg.file.NewFileIO
import com.vortexdbg.memory.Memory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.spi.Dlfcn
import com.vortexdbg.spi.SyscallHandler
import com.vortexdbg.thread.Entry
import com.vortexdbg.thread.Function64
import com.vortexdbg.unix.UnixSyscallHandler
import com.vortexdbg.unwind.SimpleARM64Unwinder
import com.vortexdbg.unwind.Unwinder
import com.sun.jna.Pointer
import keystone.Keystone
import keystone.KeystoneArchitecture
import keystone.KeystoneEncoded
import keystone.KeystoneMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const
import unicorn.UnicornConst
import java.io.File
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Date
import java.util.HashMap

abstract class AbstractARM64Emulator<T : NewFileIO>(
    processName: String?,
    rootDir: File?,
    family: Family,
    backendFactories: Collection<BackendFactory>?,
    vararg envs: String
) : AbstractEmulator<T>(true, processName, 0xfffe0000L, 0x10000, rootDir, family, backendFactories),
    ARMEmulator<T> {

    @JvmField
    protected val memory: Memory
    private val syscallHandlerField: UnixSyscallHandler<T>

    private val dlfcnField: Dlfcn

    init {
        backend.switchUserMode()

        backend.hook_add_new(object : EventMemHook {
            override fun hook(backend: Backend, address: Long, size: Int, value: Long, user: Any?, unmappedType: EventMemHook.UnmappedType): Boolean {
                log.warn("{} memory failed: address=0x{}, size={}, value=0x{}", unmappedType, java.lang.Long.toHexString(address), size, java.lang.Long.toHexString(value))
                if (LoggerFactory.getLogger(AbstractEmulator::class.java).isDebugEnabled) {
                    attach().debug(unmappedType.toString() + " memory failed: address=0x" + java.lang.Long.toHexString(address) + ", size=" + size)
                }
                return false
            }

            override fun onAttach(unHook: UnHook) {
            }

            override fun detach() {
                throw UnsupportedOperationException()
            }
        }, UnicornConst.UC_HOOK_MEM_READ_UNMAPPED or UnicornConst.UC_HOOK_MEM_WRITE_UNMAPPED or UnicornConst.UC_HOOK_MEM_FETCH_UNMAPPED, null)

        this.syscallHandlerField = createSyscallHandler(svcMemory)

        backend.enableVFP()
        @Suppress("UNCHECKED_CAST")
        this.memory = createMemory(syscallHandlerField, envs as Array<String>)
        this.dlfcnField = createDyld(svcMemory)
        this.memory.addHookListener(dlfcnField)

        backend.hook_add_new(syscallHandlerField, this)

        setupTraps()
    }

    private var arm64DisassemblerCache: Disassembler? = null
    private val disassembleCache: MutableMap<Long, Array<Instruction>> = HashMap()

    @Synchronized
    private fun createArm64Disassembler(): Disassembler {
        if (arm64DisassemblerCache == null) {
            this.arm64DisassemblerCache = DisassemblerFactory.createArm64Disassembler()
            this.arm64DisassemblerCache!!.setDetail(true)
        }
        return arm64DisassemblerCache!!
    }

    protected open fun setupTraps() {
        val size = getPageAlign()
        backend.mem_map(LR, size.toLong(), UnicornConst.UC_PROT_READ or UnicornConst.UC_PROT_EXEC)
        val buffer = ByteBuffer.allocate(size)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val code = Arm64Svc.assembleSvc(0)
        var i = 0
        while (i < size) {
            buffer.putInt(code) // svc #0
            i += 4
        }
        memory.pointer(LR).write(buffer.array())
    }

    override fun createRegisterContext(backend: Backend): RegisterContext {
        return BackendArm64RegisterContext(backend, this)
    }

    override fun getDlfcn(): Dlfcn {
        return dlfcnField
    }

    final override fun assemble(assembly: Iterable<String>): ByteArray {
        Keystone(KeystoneArchitecture.Arm64, KeystoneMode.LittleEndian).use { keystone ->
            val encoded: KeystoneEncoded = keystone.assemble(assembly)
            return encoded.getMachineCode()
        }
    }

    override fun createConsoleDebugger(): Debugger {
        return SimpleARM64Debugger(this)
    }

    override fun closeInternal() {
        syscallHandlerField.destroy()

        IOUtils.close(arm64DisassemblerCache)
        disassembleCache.clear()
    }

    override fun loadLibrary(libraryFile: File): Module {
        return memory.load(libraryFile)
    }

    override fun loadLibrary(libraryFile: File, forceCallInit: Boolean): Module {
        return memory.load(libraryFile, forceCallInit)
    }

    override fun getMemory(): Memory {
        return memory
    }

    override fun getSyscallHandler(): SyscallHandler<T> {
        return syscallHandlerField
    }

    final override fun showRegs() {
        ARM.showRegs64(this, null)
    }

    final override fun showRegs(vararg regs: Int) {
        ARM.showRegs64(this, regs)
    }

    override fun printAssemble(out: PrintStream, address: Long, size: Int, maxLengthLibraryName: Int, visitor: InstructionVisitor): Array<Instruction> {
        var insns = disassembleCache[address]
        val currentCode = backend.mem_read(address, size.toLong())
        var needUpdateCache = false
        if (insns != null) {
            val cachedCode = ByteArray(size)
            var offset = 0
            for (insn in insns) {
                val insnBytes = insn.getBytes()
                System.arraycopy(insnBytes, 0, cachedCode, offset, insnBytes.size)
                offset += insnBytes.size
            }

            if (!Arrays.equals(currentCode, cachedCode)) {
                needUpdateCache = true
            }
        } else {
            needUpdateCache = true
        }
        if (needUpdateCache) {
            insns = disassemble(address, currentCode, false, 0)
            disassembleCache[address] = insns
        }
        val result: Array<Instruction> = insns!!
        printAssemble(out, result, address, maxLengthLibraryName, visitor)
        return result
    }

    override fun disassemble(address: Long, size: Int, count: Long): Array<Instruction> {
        val code = backend.mem_read(address, size.toLong())
        return createArm64Disassembler().disasm(code, address, count)
    }

    override fun disassemble(address: Long, code: ByteArray, thumb: Boolean, count: Long): Array<Instruction> {
        if (thumb) {
            throw IllegalStateException()
        }
        return createArm64Disassembler().disasm(code, address, count)
    }

    private fun printAssemble(out: PrintStream, insns: Array<Instruction>, address: Long, maxLengthLibraryName: Int, visitor: InstructionVisitor?) {
        var addr = address
        val builder = StringBuilder()
        for (ins in insns) {
            if (visitor != null) {
                visitor.visitLast(builder)
            }
            builder.append('\n')
            builder.append(dateFormat.format(Date()))
            builder.append(ARM.assembleDetail(this, ins, addr, false, maxLengthLibraryName))
            if (visitor != null) {
                visitor.visit(builder, ins)
            }
            addr += ins.getSize()
        }
        out.print(builder)
    }

    override fun getPointerSize(): Int {
        return 8
    }

    override fun getPageAlignInternal(): Int {
        return ARMEmulator.PAGE_ALIGN
    }

    override fun eFunc(begin: Long, vararg arguments: Number): Number {
        return runMainForResult(Function64(getPid(), begin, LR, isPaddingArgument(), *arguments))!!
    }

    override fun eEntry(begin: Long, sp: Long): Number {
        return runMainForResult(Entry(getPid(), begin, LR, sp))!!
    }

    override fun getStackPointer(): Pointer {
        return VortexdbgPointer.register(this, Arm64Const.UC_ARM64_REG_SP)
    }

    override fun getUnwinder(): Unwinder {
        return SimpleARM64Unwinder(this)
    }

    override fun getReturnAddress(): Long {
        return LR
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AbstractARM64Emulator::class.java)

        private const val LR = 0x7ffff0000L
    }
}
