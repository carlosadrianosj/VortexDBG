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
import unicorn.Arm64Const
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

abstract class Arm64Hook(name: String?, private val enablePostCall: Boolean) : Arm64Svc(name) {

    protected constructor() : this(null, false)

    protected constructor(enablePostCall: Boolean) : this(null, enablePostCall)

    final override fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer {
        val code: ByteArray = if (enablePostCall) {
            Keystone(KeystoneArchitecture.Arm64, KeystoneMode.LittleEndian).use { keystone ->
                val encoded: KeystoneEncoded = keystone.assemble(
                    Arrays.asList(
                        "sub sp, sp, #0x20",
                        "stp x29, x30, [sp, #0x10]",
                        "str x13, [sp, #0x8]",
                        "svc #0x" + Integer.toHexString(svcNumber),

                        "ldr x13, [sp]",
                        "add sp, sp, #0x8",
                        "cmp x13, #0",
                        "b.eq #0x34",
                        "blr x13",
                        "mov x8, #0",
                        "mov x12, #0x" + Integer.toHexString(svcNumber),
                        "mov x16, #0x" + Integer.toHexString(Svc.POST_CALLBACK_SYSCALL_NUMBER),
                        "svc #0",

                        "ldr x13, [sp, #0x8]",
                        "ldp x29, x30, [sp, #0x10]",
                        "add sp, sp, #0x20",
                        "ret"
                    )
                )
                encoded.getMachineCode()
            }
        } else {
            val buffer = ByteBuffer.allocate(12)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(Arm64Svc.assembleSvc(svcNumber)) // svc #0xsvcNumber
            buffer.putInt(0xf84087f1.toInt()) // ldr x17, [sp], #0x8
            buffer.putInt(0xd61f0220.toInt()) // br x17: manipulated stack in handle
            buffer.array()
        }
        val name = getName()
        val pointer = svcMemory.allocate(code.size, name ?: "Arm64Hook")
        pointer.write(0L, code, 0, code.size)
        if (log.isDebugEnabled) {
            log.debug("ARM64 hook: pointer={}", pointer)
        }
        return pointer
    }

    final override fun handle(emulator: Emulator<*>): Long {
        val backend: Backend = emulator.getBackend()
        var sp = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_SP)
        try {
            val status = doHook(emulator)
            sp = sp.share(-8L, 0L)
            if (status.forward || !enablePostCall) {
                if (log.isDebugEnabled) {
                    log.debug("ARM64 hook: sp={}, jump=0x{}", sp, java.lang.Long.toHexString(status.jump))
                }
                sp.setLong(0L, status.jump)
            } else {
                sp.setLong(0L, 0L)
            }

            return status.returnValue
        } finally {
            backend.reg_write(Arm64Const.UC_ARM64_REG_SP, sp.peer)
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
        private val log: Logger = LoggerFactory.getLogger(Arm64Hook::class.java)
    }

}
