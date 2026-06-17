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
import com.vortexdbg.arm.context.BackendArm32RegisterContext
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.debugger.Debugger
import com.vortexdbg.file.NewFileIO
import com.vortexdbg.memory.Memory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.spi.Dlfcn
import com.vortexdbg.spi.SyscallHandler
import com.vortexdbg.thread.Entry
import com.vortexdbg.thread.Function32
import com.vortexdbg.unix.UnixSyscallHandler
import com.vortexdbg.unwind.SimpleARMUnwinder
import com.vortexdbg.unwind.Unwinder
import com.sun.jna.Pointer
import keystone.Keystone
import keystone.KeystoneArchitecture
import keystone.KeystoneEncoded
import keystone.KeystoneMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.ArmConst
import unicorn.UnicornConst
import java.io.File
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Date
import java.util.HashMap

abstract class AbstractARMEmulator<T : NewFileIO>(
    processName: String?,
    rootDir: File?,
    family: Family,
    backendFactories: Collection<BackendFactory>?,
    vararg envs: String
) : AbstractEmulator<T>(false, processName, 0xfffe0000L, 0x10000, rootDir, family, backendFactories),
    ARMEmulator<T> {

    @JvmField
    protected val memory: Memory
    private val syscallHandlerField: UnixSyscallHandler<T>

    private val dlfcnField: Dlfcn

    init {
        backend.switchUserMode()

        backend.hook_add_new(object : EventMemHook {
            override fun hook(backend: Backend, address: Long, size: Int, value: Long, user: Any?, unmappedType: EventMemHook.UnmappedType): Boolean {
                val context = getContext<RegisterContext>()
                log.warn("{} memory failed: address=0x{}, size={}, value=0x{}, PC={}, LR={}", unmappedType, java.lang.Long.toHexString(address), size, java.lang.Long.toHexString(value), context.getPCPointer(), context.getLRPointer())
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

    private var armDisassemblerCache: Disassembler? = null
    private var thumbDisassemblerCache: Disassembler? = null
    private val disassembleCache: MutableMap<Long, Array<Instruction>> = HashMap()

    @Synchronized
    private fun createThumbCapstone(): Disassembler {
        if (thumbDisassemblerCache == null) {
            this.thumbDisassemblerCache = DisassemblerFactory.createArmDisassembler(true)
            this.thumbDisassemblerCache!!.setDetail(true)
        }
        return thumbDisassemblerCache!!
    }

    @Synchronized
    private fun createArmCapstone(): Disassembler {
        if (armDisassemblerCache == null) {
            this.armDisassemblerCache = DisassemblerFactory.createArmDisassembler(false)
            this.armDisassemblerCache!!.setDetail(true)
        }
        return armDisassemblerCache!!
    }

    override fun createRegisterContext(backend: Backend): RegisterContext {
        return BackendArm32RegisterContext(backend, this)
    }

    override fun getDlfcn(): Dlfcn {
        return dlfcnField
    }

    protected open fun setupTraps() {
        val size = 0x10000
        backend.mem_map(LR, size.toLong(), UnicornConst.UC_PROT_READ or UnicornConst.UC_PROT_EXEC)
        val code = ArmSvc.assembleSvc(0)
        val buffer = ByteBuffer.allocate(size)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < size) {
            buffer.putInt(code) // svc #0
            i += 4
        }
        memory.pointer(LR).write(buffer.array())
    }

    final override fun assemble(assembly: Iterable<String>): ByteArray {
        Keystone(KeystoneArchitecture.Arm, KeystoneMode.Arm).use { keystone ->
            val encoded: KeystoneEncoded = keystone.assemble(assembly)
            return encoded.getMachineCode()
        }
    }

    override fun createConsoleDebugger(): Debugger {
        return SimpleARMDebugger(this)
    }

    override fun closeInternal() {
        syscallHandlerField.destroy()

        IOUtils.close(thumbDisassemblerCache)
        IOUtils.close(armDisassemblerCache)
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
        ARM.showRegs(this, null)
    }

    final override fun showRegs(vararg regs: Int) {
        ARM.showRegs(this, regs)
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
            insns = disassemble(address, size, 0)
            disassembleCache[address] = insns
        }
        val result: Array<Instruction> = insns!!
        printAssemble(out, result, address, ARM.isThumb(backend), maxLengthLibraryName, visitor)
        return result
    }

    override fun disassemble(address: Long, size: Int, count: Long): Array<Instruction> {
        val thumb = ARM.isThumb(backend)
        val code = backend.mem_read(address, size.toLong())
        return if (thumb) createThumbCapstone().disasm(code, address, count) else createArmCapstone().disasm(code, address, count)
    }

    override fun disassemble(address: Long, code: ByteArray, thumb: Boolean, count: Long): Array<Instruction> {
        return if (thumb) createThumbCapstone().disasm(code, address, count) else createArmCapstone().disasm(code, address, count)
    }

    private fun printAssemble(out: PrintStream, insns: Array<Instruction>, address: Long, thumb: Boolean, maxLengthLibraryName: Int, visitor: InstructionVisitor?) {
        var addr = address
        val builder = StringBuilder()
        for (ins in insns) {
            if (visitor != null) {
                visitor.visitLast(builder)
            }
            builder.append('\n')
            builder.append(dateFormat.format(Date()))
            builder.append(ARM.assembleDetail(this, ins, addr, thumb, maxLengthLibraryName))
            if (visitor != null) {
                visitor.visit(builder, ins)
            }
            addr += ins.getSize()
        }
        out.print(builder)
    }

    override fun eFunc(begin: Long, vararg arguments: Number): Number {
        return runMainForResult(Function32(getPid(), begin, LR, isPaddingArgument(), *arguments))!!
    }

    override fun eEntry(begin: Long, sp: Long): Number {
        return runMainForResult(Entry(getPid(), begin, LR, sp))!!
    }

    override fun getPointerSize(): Int {
        return 4
    }

    override fun getPageAlignInternal(): Int {
        return ARMEmulator.PAGE_ALIGN
    }

    override fun getStackPointer(): Pointer {
        return VortexdbgPointer.register(this, ArmConst.UC_ARM_REG_SP)
    }

    override fun getUnwinder(): Unwinder {
        return SimpleARMUnwinder(this)
    }

    override fun getReturnAddress(): Long {
        return LR
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AbstractARMEmulator::class.java)

        private const val LR = 0xffff0000L
    }
}
