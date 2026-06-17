package com.vortexdbg.linux.thread

import com.vortexdbg.Emulator
import com.vortexdbg.Svc
import com.vortexdbg.arm.Arm64Svc
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.unix.ThreadJoinVisitor
import com.sun.jna.Pointer
import keystone.Keystone
import keystone.KeystoneArchitecture
import keystone.KeystoneEncoded
import keystone.KeystoneMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const

import java.util.Arrays
import java.util.concurrent.atomic.AtomicLong

internal class ClonePatcher64(private val visitor: ThreadJoinVisitor?, private val value_ptr: AtomicLong) : Arm64Svc() {

    private var threadId = 0

    override fun handle(emulator: Emulator<*>): Long {
        val context = emulator.getContext<RegisterContext>()
        val pthread_start = context.getPointerArg(0)
        val child_stack = context.getPointerArg(1)
        val flags = context.getIntArg(2)
        val thread = context.getPointerArg(3)

        val start_routine = thread!!.getPointer(0x60L)
        val arg = thread.getPointer(0x68L)
        log.info("clone start_routine={}, child_stack={}, flags=0x{}, arg={}, pthread_start={}", start_routine, child_stack, Integer.toHexString(flags), arg, pthread_start)

        val backend = emulator.getBackend()
        val join = visitor == null || visitor.canJoin(start_routine, ++threadId)
        var pointer = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_SP)
        try {
            pointer = pointer.share(-8L, 0L) // threadId
            pointer.setLong(0L, threadId.toLong())

            if (join) {
                pointer = pointer.share(-8L, 0L)
                pointer.setPointer(0L, start_routine)

                pointer = pointer.share(-8L, 0L)
                pointer.setPointer(0L, arg)
            }

            pointer = pointer.share(-8L, 0L) // can join
            pointer.setLong(0L, if (join) 1L else 0L)
        } finally {
            backend.reg_write(Arm64Const.UC_ARM64_REG_SP, pointer.peer)
        }
        return 0
    }

    override fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer {
        Keystone(KeystoneArchitecture.Arm64, KeystoneMode.LittleEndian).use { keystone ->
            val encoded = keystone.assemble(Arrays.asList(
                "sub sp, sp, #0x10",
                "stp x29, x30, [sp]",
                "svc #0x" + Integer.toHexString(svcNumber),

                "ldr x13, [sp]",
                "add sp, sp, #0x8",
                "cmp x13, #0",
                "b.eq #0x48",

                "ldp x0, x13, [sp]",
                "add sp, sp, #0x10",

                "mov x8, #0",
                "mov x12, #0x" + Integer.toHexString(svcNumber),
                "mov x16, #0x" + Integer.toHexString(Svc.PRE_CALLBACK_SYSCALL_NUMBER),
                "svc #0",

                "blr x13",

                "mov x8, #0",
                "mov x12, #0x" + Integer.toHexString(svcNumber),
                "mov x16, #0x" + Integer.toHexString(Svc.POST_CALLBACK_SYSCALL_NUMBER),
                "svc #0",

                "ldr x0, [sp]",
                "add sp, sp, #0x8",

                "ldp x29, x30, [sp]",
                "add sp, sp, #0x10",
                "ret"))
            val code = encoded.machineCode
            val pointer = svcMemory.allocate(code.size, javaClass.getSimpleName())
            pointer.write(code)
            return pointer
        }
    }

    override fun handlePreCallback(emulator: Emulator<*>) {
        if (visitor!!.isSaveContext()) {
            emulator.pushContext(0x4)
        }
    }

    override fun handlePostCallback(emulator: Emulator<*>) {
        super.handlePostCallback(emulator)
        value_ptr.set(emulator.getContext<RegisterContext>().getLongArg(0))
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ClonePatcher64::class.java)
    }
}
