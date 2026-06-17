package com.vortexdbg.linux.thread

import com.vortexdbg.Emulator
import com.vortexdbg.Svc
import com.vortexdbg.arm.ArmSvc
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
import unicorn.ArmConst

import java.util.Arrays
import java.util.concurrent.atomic.AtomicLong

internal class ClonePatcher32(private val visitor: ThreadJoinVisitor?, private val value_ptr: AtomicLong) : ArmSvc() {

    private var threadId = 0

    override fun handle(emulator: Emulator<*>): Long {
        val context = emulator.getContext<RegisterContext>()
        val pthread_start = context.getPointerArg(0)
        val child_stack = context.getPointerArg(1)
        val flags = context.getIntArg(2)
        val thread = context.getPointerArg(3)

        val start_routine = thread!!.getPointer(0x30L)
        val arg = thread.getPointer(0x34L)
        log.info("clone start_routine={}, child_stack={}, flags=0x{}, arg={}, pthread_start={}", start_routine, child_stack, Integer.toHexString(flags), arg, pthread_start)

        val backend = emulator.getBackend()
        val join = visitor == null || visitor.canJoin(start_routine, ++threadId)
        var pointer = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_SP)
        try {
            pointer = pointer.share(-4L, 0L) // threadId
            pointer.setInt(0L, threadId)

            if (join) {
                pointer = pointer.share(-4L, 0L)
                pointer.setPointer(0L, start_routine)

                pointer = pointer.share(-4L, 0L)
                pointer.setPointer(0L, arg)
            }

            pointer = pointer.share(-4L, 0L) // can join
            pointer.setInt(0L, if (join) 1 else 0)
        } finally {
            backend.reg_write(ArmConst.UC_ARM_REG_SP, pointer.peer)
        }
        return 0
    }

    override fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer {
        Keystone(KeystoneArchitecture.Arm, KeystoneMode.Arm).use { keystone ->
            val encoded = keystone.assemble(Arrays.asList(
                "push {r4-r7, lr}",
                "svc #0x" + Integer.toHexString(svcNumber),

                "pop {r7}",
                "cmp r7, #0",
                "popeq {r0, r4-r7, pc}",
                "pop {r0, ip}",

                "mov r7, #0",
                "mov r5, #0x" + Integer.toHexString(Svc.PRE_CALLBACK_SYSCALL_NUMBER),
                "mov r4, #0x" + Integer.toHexString(svcNumber),
                "svc #0",

                "blx ip",

                "mov r7, #0",
                "mov r5, #0x" + Integer.toHexString(Svc.POST_CALLBACK_SYSCALL_NUMBER),
                "mov r4, #0x" + Integer.toHexString(svcNumber),
                "svc #0",

                "pop {r0, r4-r7, pc}"))
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
        value_ptr.set(emulator.getContext<RegisterContext>().getIntArg(0).toLong())
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ClonePatcher32::class.java)
    }
}
