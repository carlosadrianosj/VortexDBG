package com.vortexdbg.linux.thread

import com.vortexdbg.Emulator
import com.vortexdbg.Svc
import com.vortexdbg.arm.ArmSvc
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.context.EditableArm32RegisterContext
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
import java.util.concurrent.atomic.AtomicInteger

internal class ThreadClonePatcher32(private val visitor: ThreadJoinVisitor?, private val value_ptr: AtomicInteger) : ArmSvc() {

    private var threadId = 0

    override fun handle(emulator: Emulator<*>): Long {
        val context = emulator.getContext<EditableArm32RegisterContext>()
        val start_routine = context.getPointerArg(0)
        val child_stack = context.getPointerArg(1)
        val flags = context.getIntArg(2)
        val arg = context.getPointerArg(3)
        log.info("pthread_clone start_routine={}, child_stack={}, flags=0x{}, arg={}", start_routine, child_stack, Integer.toHexString(flags), arg)

        val backend = emulator.getBackend()
        val join = visitor == null || visitor.canJoin(start_routine!!, ++threadId)
        var pointer = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_SP)
        try {
            pointer = pointer.share(-4L, 0L) // threadId
            pointer.setInt(0L, threadId)

            pointer = pointer.share(-4L, 0L) // can join
            pointer.setInt(0L, if (join) 1 else 0)
        } finally {
            backend.reg_write(ArmConst.UC_ARM_REG_SP, pointer.peer)
        }
        return context.getR0Int().toLong()
    }

    override fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer {
        Keystone(KeystoneArchitecture.Arm, KeystoneMode.Arm).use { keystone ->
            val encoded = keystone.assemble(Arrays.asList(
                "push {r4-r7, lr}",
                "svc #0x" + Integer.toHexString(svcNumber),

                "pop {r7}",
                "cmp r7, #0",
                "popeq {r0, r4-r7, pc}",
                "mov ip, r0",
                "mov r0, r3",

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
        value_ptr.set(emulator.getContext<EditableArm32RegisterContext>().getIntArg(0))
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ThreadClonePatcher32::class.java)
    }
}
