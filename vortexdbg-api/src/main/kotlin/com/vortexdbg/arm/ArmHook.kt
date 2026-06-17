package com.vortexdbg.arm

import com.vortexdbg.Emulator
import com.vortexdbg.Svc
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import keystone.Keystone
import keystone.KeystoneArchitecture
import keystone.KeystoneEncoded
import keystone.KeystoneMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.ArmConst
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

abstract class ArmHook(name: String?, private val enablePostCall: Boolean) : ArmSvc(name) {

    protected constructor() : this(null, false)

    protected constructor(enablePostCall: Boolean) : this(null, enablePostCall)

    final override fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer {
        val code: ByteArray = if (enablePostCall) {
            Keystone(KeystoneArchitecture.Arm, KeystoneMode.Arm).use { keystone ->
                val encoded: KeystoneEncoded = keystone.assemble(
                    Arrays.asList(
                        "push {r4-r7, lr}",
                        "svc #0x" + Integer.toHexString(svcNumber),
                        "pop {r7}",
                        "cmp r7, #0",
                        "beq 0x28",
                        "blx r7",
                        "mov r7, #0",
                        "mov r5, #0x" + Integer.toHexString(Svc.POST_CALLBACK_SYSCALL_NUMBER),
                        "mov r4, #0x" + Integer.toHexString(svcNumber),
                        "svc #0",
                        "pop {r4-r7, pc}"
                    )
                )
                encoded.getMachineCode()
            }
        } else {
            val buffer = ByteBuffer.allocate(8)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(ArmSvc.assembleSvc(svcNumber)) // svc #0xsvcNumber
            buffer.putInt(0xe49df004.toInt()) // pop {pc}: manipulated stack in handle
            buffer.array()
        }
        val name = getName()
        val pointer = svcMemory.allocate(code.size, name ?: "ArmHook")
        pointer.write(0L, code, 0, code.size)
        if (log.isDebugEnabled) {
            log.debug("ARM hook: pointer={}", pointer)
        }
        return pointer
    }

    final override fun handle(emulator: Emulator<*>): Long {
        val backend: Backend = emulator.getBackend()
        var sp = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_SP)
        try {
            val status = doHook(emulator)
            if (status.forward || !enablePostCall) {
                sp = sp.share(-4L, 0L)
                sp.setInt(0L, status.jump.toInt())
            } else {
                sp = sp.share(-4L, 0L)
                sp.setInt(0L, 0)
            }

            return status.returnValue
        } finally {
            backend.reg_write(ArmConst.UC_ARM_REG_SP, sp.peer)
        }
    }

    private fun doHook(emulator: Emulator<*>): HookStatus {
        return try {
            hook(emulator)
        } catch (run: NestedRun) {
            HookStatus.RET(emulator, run.pc)
        }
    }

    @Throws(NestedRun::class)
    protected abstract fun hook(emulator: Emulator<*>): HookStatus

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ArmHook::class.java)
    }

}
