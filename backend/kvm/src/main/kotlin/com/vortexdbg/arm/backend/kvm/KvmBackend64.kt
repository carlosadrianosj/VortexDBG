package com.vortexdbg.arm.backend.kvm

import capstone.api.Disassembler
import capstone.api.DisassemblerFactory
import capstone.api.Instruction
import capstone.api.arm64.OpInfo
import capstone.api.arm64.OpValue
import capstone.api.arm64.Operand
import com.alibaba.fastjson.util.IOUtils
import com.vortexdbg.Emulator
import com.vortexdbg.Family
import com.vortexdbg.arm.ARMEmulator
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.arm.backend.DebugHook
import com.vortexdbg.arm.backend.KvmBackend
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import keystone.Keystone
import keystone.KeystoneArchitecture
import keystone.KeystoneEncoded
import keystone.KeystoneMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const
import unicorn.UnicornConst

import java.nio.ByteBuffer
import java.nio.ByteOrder

class KvmBackend64(emulator: Emulator<*>, kvm: Kvm) : KvmBackend(emulator, kvm) {

    override fun onInitialize() {
        super.onInitialize()

        mem_map(KvmBackend.REG_VBAR_EL1, getPageSize().toLong(), UnicornConst.UC_PROT_READ or UnicornConst.UC_PROT_EXEC)
        val buffer = ByteBuffer.allocate(getPageSize())
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.hasRemaining()) {
            if (buffer.position() == 0x400) {
                buffer.putInt(0x390003e0) // strb w0, [sp]
            } else {
                buffer.putInt(0x390003e1) // strb w1, [sp]
            }
            if (buffer.hasRemaining()) {
                buffer.putInt(0xd69f03e0.toInt()) // eret
            }
        }
        val ptr = VortexdbgPointer.pointer(emulator, KvmBackend.REG_VBAR_EL1)
        assert(ptr != null)
        ptr!!.write(buffer.array())
    }

    private var disassembler: Disassembler? = null

    @Synchronized
    private fun createDisassembler(): Disassembler {
        if (disassembler == null) {
            this.disassembler = DisassemblerFactory.createArm64Disassembler()
            this.disassembler!!.setDetail(true)
        }
        return disassembler!!
    }

    private fun handleCommRead(vaddr: Long, elr: Long) {
        val pointer = VortexdbgPointer.pointer(emulator, vaddr)
        assert(pointer != null)
        val pc = VortexdbgPointer.pointer(emulator, elr)
        assert(pc != null)
        val code = pc!!.getByteArray(0L, 4)
        val insn = createDisassembler().disasm(code, elr, 1L)[0]
        if (log.isDebugEnabled) {
            log.debug("handleCommRead vaddr=0x{}, elr=0x{}, asm={}", java.lang.Long.toHexString(vaddr), java.lang.Long.toHexString(elr), insn)
        }
        val opInfo = insn.operands as OpInfo
        if (opInfo.isUpdateFlags || opInfo.isWriteBack || !insn.mnemonic.startsWith("ldr") || vaddr < _COMM_PAGE64_BASE_ADDRESS) {
            throw UnsupportedOperationException()
        }
        val op = opInfo.operands
        val offset = (vaddr - _COMM_PAGE64_BASE_ADDRESS).toInt()
        when (offset) {
            0x38, // uint64_t max memory size */
            0x40,
            0x58 -> {
                val operand = op[0]
                val value = operand.value
                reg_write(insn.mapToUnicornReg(value.reg), 0x0L)
                kvm.reg_set_elr_el1(elr + 4)
                return
            }
            0x48,
            0x4c,
            0x50,
            0x60,
            0x64,
            0x90 -> {
                val operand = op[0]
                val value = operand.value
                reg_write(insn.mapToUnicornReg(value.reg), 0x0)
                kvm.reg_set_elr_el1(elr + 4)
                return
            }
            0x22, // uint8_t number of configured CPUs
            0x34, // uint8_t number of active CPUs (hw.activecpu)
            0x35, // uint8_t number of physical CPUs (hw.physicalcpu_max)
            0x36 -> { // uint8_t number of logical CPUs (hw.logicalcpu_max)
                val operand = op[0]
                val value = operand.value
                reg_write(insn.mapToUnicornReg(value.reg), 1)
                kvm.reg_set_elr_el1(elr + 4)
                return
            }
            else ->
                throw UnsupportedOperationException("vaddr=0x" + java.lang.Long.toHexString(vaddr))
        }
    }

    override fun handleException(esr: Long, far: Long, elr: Long, spsr: Long, pc: Long): Boolean {
        val ec = ((esr shr 26) and 0x3fL).toInt()
        if (log.isDebugEnabled) {
            log.debug("handleException syndrome=0x{}, far=0x{}, elr=0x{}, ec=0x{}, pc=0x{}", java.lang.Long.toHexString(esr), java.lang.Long.toHexString(far), java.lang.Long.toHexString(elr), Integer.toHexString(ec), java.lang.Long.toHexString(pc))
        }
        when (ec) {
            EC_AA64_SVC -> {
                val swi = (esr and 0xffffL).toInt()
                callSVC(elr, swi)
                return true
            }
            EC_AA64_BKPT -> {
                val bkpt = (esr and 0xffffL).toInt()
                interruptHookNotifier!!.notifyCallSVC(this, ARMEmulator.EXCP_BKPT, bkpt)
                return true
            }
            KvmCallback.EC_DATAABORT -> {
                val isv = (esr and KvmCallback.ARM_EL_ISV.toLong()) != 0L
                val isWrite = ((esr shr 6) and 1L) != 0L
                val s1ptw = ((esr shr 7) and 1L) != 0L
                val sas = ((esr shr 22) and 3L).toInt()
                val len = 1 shl sas
                val srt = ((esr shr 16) and 0x1fL).toInt()
                val dfsc = (esr and 0x3fL).toInt()
                if (log.isDebugEnabled) {
                    log.debug("handle EC_DATAABORT isv={}, isWrite={}, s1ptw={}, len={}, srt={}, dfsc=0x{}, vaddr=0x{}", isv, isWrite, s1ptw, len, srt, Integer.toHexString(dfsc), java.lang.Long.toHexString(far))
                }
                throw UnsupportedOperationException("handleException ec=0x" + Integer.toHexString(ec) + ", dfsc=0x" + Integer.toHexString(dfsc))
            }
            else ->
                throw UnsupportedOperationException("handleException ec=0x" + Integer.toHexString(ec))
        }
    }

    @Throws(BackendException::class)
    override fun mem_map(address: Long, size: Long, perms: Int) {
        if (address == DARWIN_KERNEL_BASE) {
            throw BackendException()
        }

        super.mem_map(address, size, perms)
    }

    override fun switchUserMode() {
    }

    override fun enableVFP() {
        enableVFP(true)
    }

    @Throws(BackendException::class)
    override fun reg_read(regId: Int): Number {
        try {
            when (regId) {
                Arm64Const.UC_ARM64_REG_X0,
                Arm64Const.UC_ARM64_REG_X1,
                Arm64Const.UC_ARM64_REG_X2,
                Arm64Const.UC_ARM64_REG_X3,
                Arm64Const.UC_ARM64_REG_X4,
                Arm64Const.UC_ARM64_REG_X5,
                Arm64Const.UC_ARM64_REG_X6,
                Arm64Const.UC_ARM64_REG_X7,
                Arm64Const.UC_ARM64_REG_X8,
                Arm64Const.UC_ARM64_REG_X9,
                Arm64Const.UC_ARM64_REG_X10,
                Arm64Const.UC_ARM64_REG_X11,
                Arm64Const.UC_ARM64_REG_X12,
                Arm64Const.UC_ARM64_REG_X13,
                Arm64Const.UC_ARM64_REG_X14,
                Arm64Const.UC_ARM64_REG_X15,
                Arm64Const.UC_ARM64_REG_X16,
                Arm64Const.UC_ARM64_REG_X17,
                Arm64Const.UC_ARM64_REG_X18,
                Arm64Const.UC_ARM64_REG_X19,
                Arm64Const.UC_ARM64_REG_X20,
                Arm64Const.UC_ARM64_REG_X21,
                Arm64Const.UC_ARM64_REG_X22,
                Arm64Const.UC_ARM64_REG_X23,
                Arm64Const.UC_ARM64_REG_X24,
                Arm64Const.UC_ARM64_REG_X25,
                Arm64Const.UC_ARM64_REG_X26,
                Arm64Const.UC_ARM64_REG_X27,
                Arm64Const.UC_ARM64_REG_X28 ->
                    return kvm.reg_read64(regId - Arm64Const.UC_ARM64_REG_X0)
                Arm64Const.UC_ARM64_REG_W0,
                Arm64Const.UC_ARM64_REG_W1,
                Arm64Const.UC_ARM64_REG_W2,
                Arm64Const.UC_ARM64_REG_W3,
                Arm64Const.UC_ARM64_REG_W4,
                Arm64Const.UC_ARM64_REG_W5,
                Arm64Const.UC_ARM64_REG_W6,
                Arm64Const.UC_ARM64_REG_W7,
                Arm64Const.UC_ARM64_REG_W8,
                Arm64Const.UC_ARM64_REG_W9,
                Arm64Const.UC_ARM64_REG_W10,
                Arm64Const.UC_ARM64_REG_W11,
                Arm64Const.UC_ARM64_REG_W12,
                Arm64Const.UC_ARM64_REG_W13,
                Arm64Const.UC_ARM64_REG_W14,
                Arm64Const.UC_ARM64_REG_W15,
                Arm64Const.UC_ARM64_REG_W16,
                Arm64Const.UC_ARM64_REG_W17,
                Arm64Const.UC_ARM64_REG_W18,
                Arm64Const.UC_ARM64_REG_W19,
                Arm64Const.UC_ARM64_REG_W20,
                Arm64Const.UC_ARM64_REG_W21,
                Arm64Const.UC_ARM64_REG_W22,
                Arm64Const.UC_ARM64_REG_W23,
                Arm64Const.UC_ARM64_REG_W24,
                Arm64Const.UC_ARM64_REG_W25,
                Arm64Const.UC_ARM64_REG_W26,
                Arm64Const.UC_ARM64_REG_W27,
                Arm64Const.UC_ARM64_REG_W28,
                Arm64Const.UC_ARM64_REG_W29,
                Arm64Const.UC_ARM64_REG_W30 ->
                    return (kvm.reg_read64(regId - Arm64Const.UC_ARM64_REG_W0) and 0xffffffffL).toInt()
                Arm64Const.UC_ARM64_REG_CPACR_EL1 ->
                    return kvm.reg_read_cpacr_el1()
                Arm64Const.UC_ARM64_REG_SP ->
                    return kvm.reg_read_sp64()
                Arm64Const.UC_ARM64_REG_PC ->
                    return kvm.reg_read_pc64()
                Arm64Const.UC_ARM64_REG_FP ->
                    return kvm.reg_read64(29)
                Arm64Const.UC_ARM64_REG_LR ->
                    return kvm.reg_read64(30)
                Arm64Const.UC_ARM64_REG_NZCV ->
                    return kvm.reg_read_nzcv()
                else ->
                    throw KvmException("regId=$regId")
            }
        } catch (e: KvmException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun reg_write(regId: Int, value: Number) {
        try {
            when (regId) {
                Arm64Const.UC_ARM64_REG_X0,
                Arm64Const.UC_ARM64_REG_X1,
                Arm64Const.UC_ARM64_REG_X2,
                Arm64Const.UC_ARM64_REG_X3,
                Arm64Const.UC_ARM64_REG_X4,
                Arm64Const.UC_ARM64_REG_X5,
                Arm64Const.UC_ARM64_REG_X6,
                Arm64Const.UC_ARM64_REG_X7,
                Arm64Const.UC_ARM64_REG_X8,
                Arm64Const.UC_ARM64_REG_X9,
                Arm64Const.UC_ARM64_REG_X10,
                Arm64Const.UC_ARM64_REG_X11,
                Arm64Const.UC_ARM64_REG_X12,
                Arm64Const.UC_ARM64_REG_X13,
                Arm64Const.UC_ARM64_REG_X14,
                Arm64Const.UC_ARM64_REG_X15,
                Arm64Const.UC_ARM64_REG_X16,
                Arm64Const.UC_ARM64_REG_X17,
                Arm64Const.UC_ARM64_REG_X18,
                Arm64Const.UC_ARM64_REG_X19,
                Arm64Const.UC_ARM64_REG_X20,
                Arm64Const.UC_ARM64_REG_X21,
                Arm64Const.UC_ARM64_REG_X22,
                Arm64Const.UC_ARM64_REG_X23,
                Arm64Const.UC_ARM64_REG_X24,
                Arm64Const.UC_ARM64_REG_X25,
                Arm64Const.UC_ARM64_REG_X26,
                Arm64Const.UC_ARM64_REG_X27,
                Arm64Const.UC_ARM64_REG_X28 ->
                    kvm.reg_write64(regId - Arm64Const.UC_ARM64_REG_X0, value.toLong())
                Arm64Const.UC_ARM64_REG_W0,
                Arm64Const.UC_ARM64_REG_W1,
                Arm64Const.UC_ARM64_REG_W2,
                Arm64Const.UC_ARM64_REG_W3,
                Arm64Const.UC_ARM64_REG_W4,
                Arm64Const.UC_ARM64_REG_W5,
                Arm64Const.UC_ARM64_REG_W6,
                Arm64Const.UC_ARM64_REG_W7,
                Arm64Const.UC_ARM64_REG_W8,
                Arm64Const.UC_ARM64_REG_W9,
                Arm64Const.UC_ARM64_REG_W10,
                Arm64Const.UC_ARM64_REG_W11,
                Arm64Const.UC_ARM64_REG_W12,
                Arm64Const.UC_ARM64_REG_W13,
                Arm64Const.UC_ARM64_REG_W14,
                Arm64Const.UC_ARM64_REG_W15,
                Arm64Const.UC_ARM64_REG_W16,
                Arm64Const.UC_ARM64_REG_W17,
                Arm64Const.UC_ARM64_REG_W18,
                Arm64Const.UC_ARM64_REG_W19,
                Arm64Const.UC_ARM64_REG_W20,
                Arm64Const.UC_ARM64_REG_W21,
                Arm64Const.UC_ARM64_REG_W22,
                Arm64Const.UC_ARM64_REG_W23,
                Arm64Const.UC_ARM64_REG_W24,
                Arm64Const.UC_ARM64_REG_W25,
                Arm64Const.UC_ARM64_REG_W26,
                Arm64Const.UC_ARM64_REG_W27,
                Arm64Const.UC_ARM64_REG_W28,
                Arm64Const.UC_ARM64_REG_W29,
                Arm64Const.UC_ARM64_REG_W30 ->
                    kvm.reg_write64(regId - Arm64Const.UC_ARM64_REG_W0, value.toLong())
                Arm64Const.UC_ARM64_REG_CPACR_EL1 ->
                    kvm.reg_set_cpacr_el1(value.toLong())
                Arm64Const.UC_ARM64_REG_SP ->
                    kvm.reg_set_sp64(value.toLong())
                Arm64Const.UC_ARM64_REG_X29 ->
                    kvm.reg_write64(29, value.toLong())
                Arm64Const.UC_ARM64_REG_TPIDR_EL0 ->
                    kvm.reg_set_tpidr_el0(value.toLong())
                Arm64Const.UC_ARM64_REG_LR ->
                    kvm.reg_write64(30, value.toLong())
                Arm64Const.UC_ARM64_REG_TPIDRRO_EL0 ->
                    kvm.reg_set_tpidrro_el0(value.toLong())
                Arm64Const.UC_ARM64_REG_NZCV ->
                    kvm.reg_set_nzcv(value.toLong())
                else ->
                    throw KvmException("regId=$regId")
            }
        } catch (e: KvmException) {
            throw BackendException(e)
        }
    }

    @Throws(BackendException::class)
    override fun reg_read_vector(regId: Int): ByteArray {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun reg_write_vector(regId: Int, vector: ByteArray) {
        throw UnsupportedOperationException()
    }

    @Throws(BackendException::class)
    override fun debugger_add(callback: DebugHook, begin: Long, end: Long, user_data: Any?) {
    }

    override fun addSoftBreakPoint(address: Long, svcNumber: Int, thumb: Boolean): ByteArray {
        Keystone(KeystoneArchitecture.Arm64, KeystoneMode.LittleEndian).use { keystone ->
            val encoded = keystone.assemble("brk #$svcNumber")
            return encoded.machineCode
        }
    }

    @Synchronized
    @Throws(BackendException::class)
    override fun destroy() {
        super.destroy()

        IOUtils.close(disassembler)
        disassembler = null
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(KvmBackend64::class.java)

        private const val EC_AA64_SVC = 0x15
        private const val EC_AA64_BKPT = 0x3c

        @JvmStatic
        private val DARWIN_KERNEL_BASE: Long = 0xffffff80001f0000uL.toLong()

        @JvmStatic
        private val _COMM_PAGE64_BASE_ADDRESS: Long = DARWIN_KERNEL_BASE + 0xc000L /* In TTBR0 */
    }
}
