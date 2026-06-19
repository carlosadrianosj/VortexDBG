package com.vortexdbg.arm

import capstone.api.DisassemblerFactory
import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.hook.HookCallback
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import keystone.Keystone
import keystone.KeystoneArchitecture
import keystone.KeystoneEncoded
import keystone.KeystoneMode
import unicorn.ArmConst
import java.io.IOException
import java.util.Arrays

/**
 * Patches a function's entry-point prologue in place with an SVC trap so a
 * callback runs when the function is invoked.
 */
@Suppress("unused")
object InlineHook {

    /**
     * Hooks a Thumb function entry by overwriting its prologue with an SVC trap.
     * Only supports entries whose first instruction is `push {r4, r5, r6, r7, lr}`.
     */
    @JvmStatic
    fun simpleThumbHook(emulator: Emulator<*>, address: Long, callback: HookCallback?) {
        val backend: Backend = emulator.getBackend()
        val pointer: VortexdbgPointer = VortexdbgPointer.pointer(emulator, address)
            ?: throw IllegalArgumentException()
        try {
            DisassemblerFactory.createArmDisassembler(true).use { disassembler ->
                disassembler.setDetail(true)

                val code = readThumbCode(pointer)
                val insns = disassembler.disasm(code, 0, 1)
                if (insns == null || insns.size < 1) {
                    throw IllegalArgumentException("Invalid hook address: $pointer")
                }
                val insn = insns[0]
                val asm = insn.toString()
                if ("push {r4, r5, r6, r7, lr}" != asm) {
                    throw IllegalArgumentException("Invalid hook address: $pointer, asm: $asm")
                }

                emulator.getSvcMemory().registerSvc(object : ThumbSvc() {
                    override fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer {
                        if (svcNumber < 0 || svcNumber > 0xff) {
                            throw IllegalStateException("service number out of range")
                        }

                        Keystone(KeystoneArchitecture.Arm, KeystoneMode.ArmThumb).use { keystone ->
                            val encoded: KeystoneEncoded = keystone.assemble(
                                Arrays.asList(
                                    "svc #0x" + Integer.toHexString(svcNumber),
                                    "mov pc, lr"
                                )
                            )
                            val code = encoded.getMachineCode()
                            pointer.write(0, code, 0, code.size)
                            return pointer
                        }
                    }

                    override fun handle(emulator: Emulator<*>): Long {
                        if (callback != null) {
                            return callback.onHook(emulator).toLong()
                        }
                        return backend.reg_read(ArmConst.UC_ARM_REG_R0).toInt().toLong()
                    }
                })
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    /**
     * Hooks an ARM function entry by overwriting its prologue with an SVC trap.
     * Only supports entries whose first instruction is the standard register-save
     * push (`push {r4-r8, sb, lr}` or `push {r4-r8, sb, sl, fp, lr}`).
     */
    @JvmStatic
    fun simpleArmHook(emulator: Emulator<*>, address: Long, callback: HookCallback?) {
        val pointer: VortexdbgPointer = VortexdbgPointer.pointer(emulator, address)
            ?: throw IllegalArgumentException()
        try {
            DisassemblerFactory.createArmDisassembler(false).use { disassembler ->
                disassembler.setDetail(true)

                val code = pointer.getByteArray(0, 4)
                val insns = disassembler.disasm(code, 0, 1)
                if (insns == null || insns.size < 1) {
                    throw IllegalArgumentException("Invalid hook address: $pointer")
                }
                val insn = insns[0]
                val asm = insn.toString()
                if ("push {r4, r5, r6, r7, r8, sb, lr}" != asm && "push {r4, r5, r6, r7, r8, sb, sl, fp, lr}" != asm) {
                    throw IllegalArgumentException("Invalid hook address: $pointer, asm: $asm")
                }

                emulator.getSvcMemory().registerSvc(object : ArmSvc() {
                    override fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer {
                        Keystone(KeystoneArchitecture.Arm, KeystoneMode.Arm).use { keystone ->
                            val encoded: KeystoneEncoded = keystone.assemble(
                                Arrays.asList(
                                    "svc #0x" + Integer.toHexString(svcNumber),
                                    "mov pc, lr"
                                )
                            )
                            val code = encoded.getMachineCode()
                            pointer.write(0, code, 0, code.size)
                            return pointer
                        }
                    }

                    override fun handle(emulator: Emulator<*>): Long {
                        if (callback != null) {
                            return callback.onHook(emulator).toLong()
                        }
                        return emulator.getBackend().reg_read(ArmConst.UC_ARM_REG_R0).toInt().toLong()
                    }
                })
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    private fun readThumbCode(pointer: Pointer): ByteArray {
        val ins = pointer.getShort(0)
        return if (ARM.isThumb32(ins)) { // thumb32
            pointer.getByteArray(0, 4)
        } else {
            pointer.getByteArray(0, 2)
        }
    }

}
