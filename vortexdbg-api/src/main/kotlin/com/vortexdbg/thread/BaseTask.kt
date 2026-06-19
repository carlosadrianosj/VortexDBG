package com.vortexdbg.thread

import com.vortexdbg.AbstractEmulator
import com.vortexdbg.Emulator
import com.vortexdbg.arm.ARM
import com.vortexdbg.arm.FunctionCall
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.pointer.VortexdbgPointer
import org.apache.commons.collections4.Bag
import org.apache.commons.collections4.bag.HashBag
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const
import unicorn.ArmConst
import java.util.Stack

abstract class BaseTask : RunnableTask {

    private var waiter: Waiter? = null
    private var stackSpaceAllocIndex = -1

    override fun setWaiter(emulator: Emulator<*>, waiter: Waiter?) {
        this.waiter = waiter

        if (waiter != null &&
            log.isTraceEnabled
        ) {
            emulator.attach().debug("setWaiter: $waiter")
        }
    }

    override fun getWaiter(): Waiter? {
        return waiter
    }

    final override fun canDispatch(): Boolean {
        val waiter = this.waiter
        if (waiter != null) {
            return waiter.canDispatch()
        }
        return true
    }

    private var context: Long = 0

    final override fun isContextSaved(): Boolean {
        return this.context != 0L
    }

    final override fun saveContext(emulator: Emulator<*>) {
        val backend = emulator.getBackend()
        if (this.context == 0L) {
            this.context = backend.context_alloc()
        }
        backend.context_save(this.context)
    }

    override fun popContext(emulator: Emulator<*>) {
        val backend = emulator.getBackend()
        val off = emulator.popContext()
        val pc: Long
        if (emulator.is32Bit()) {
            pc = backend.reg_read(ArmConst.UC_ARM_REG_PC).toInt().toLong() and 0xfffffffeL
        } else {
            pc = backend.reg_read(Arm64Const.UC_ARM64_REG_PC).toLong()
        }
        backend.reg_write(if (emulator.is32Bit()) ArmConst.UC_ARM_REG_PC else Arm64Const.UC_ARM64_REG_PC, pc + off)
        saveContext(emulator)
    }

    override fun restoreContext(emulator: Emulator<*>) {
        val backend = emulator.getBackend()
        backend.context_restore(this.context)
    }

    protected fun continueRun(emulator: AbstractEmulator<*>, until: Long): Number {
        val backend = emulator.getBackend()
        backend.context_restore(this.context)
        var pc: Long
        if (emulator.is32Bit()) {
            pc = backend.reg_read(ArmConst.UC_ARM_REG_PC).toInt().toLong() and 0xfffffffeL
            if (ARM.isThumb(backend)) {
                pc = pc or 1L
            }
        } else {
            pc = backend.reg_read(Arm64Const.UC_ARM64_REG_PC).toLong()
        }
        if (log.isDebugEnabled) {
            log.debug("continue run task={}, pc={}, until=0x{}", this, VortexdbgPointer.pointer(emulator, pc), java.lang.Long.toHexString(until))
        }
        val waiter = getWaiter()
        if (waiter != null) {
            waiter.onContinueRun(emulator)
            setWaiter(emulator, null)
        }
        return emulator.emulate(pc, until)!!
    }

    override fun destroy(emulator: Emulator<*>) {
        val backend = emulator.getBackend()

        if (stackSpaceAllocIndex != -1) {
            emulator.getMemory().freeThreadIndex(stackSpaceAllocIndex)
        }

        if (this.context != 0L) {
            backend.context_free(this.context)
            this.context = 0
        }

        if (destroyListener != null) {
            destroyListener!!.onDestroy(emulator)
        }
    }

    protected fun allocateStack(emulator: Emulator<*>): VortexdbgPointer {
        // The thread stack must come from the STACK_BASE region, not MMAP_BASE: KVM validates SP on
        // use and an address outside the expected stack range aborts the guest.
        if (stackSpaceAllocIndex == -1) {
            stackSpaceAllocIndex = emulator.getMemory().allocateThreadIndex()
        }
        return emulator.getMemory().allocateThreadStack(stackSpaceAllocIndex)
    }

    override fun setResult(emulator: Emulator<*>, ret: Number?) {
    }

    private var destroyListener: DestroyListener? = null

    override fun setDestroyListener(listener: DestroyListener?) {
        this.destroyListener = listener
    }

    private val stack = Stack<FunctionCall>()
    private val bag: Bag<Long> = HashBag()

    override fun pushFunction(emulator: Emulator<*>, call: FunctionCall) {
        stack.push(call)
        bag.add(call.returnAddress, 1)

        if (log.isDebugEnabled) {
            log.debug("pushFunction call={}, bagCount={}", call.toReadableString(emulator), bag.getCount(call.returnAddress))
        }
    }

    override fun popFunction(emulator: Emulator<*>, address: Long): FunctionCall? {
        if (!bag.contains(address)) {
            return null
        }

        val call: FunctionCall
        if (emulator.is64Bit()) { // check LR for aarch64
            call = stack.peek()
            val lr = emulator.getContext<RegisterContext>().getLR()
            if (lr != call.returnAddress) {
                return null
            }

            bag.remove(address, 1)
            stack.pop()
        } else {
            bag.remove(address, 1)
            call = stack.pop()
        }

        if (log.isDebugEnabled) {
            log.debug("popFunction call={}, address={}, stackSize={}, bagCount={}", call.toReadableString(emulator), VortexdbgPointer.pointer(emulator, address), stack.size, bag.getCount(address))
        }
        if (call.returnAddress != address) {
            for (fc in stack) {
                log.warn("stackCall call={}, bagCount={}", fc.toReadableString(emulator), bag.getCount(fc.returnAddress))
            }
        }
        return call
    }

    final override fun toString(): String {
        return getStatus() + "|" + toThreadString()
    }

    protected abstract fun getStatus(): String
    protected abstract fun toThreadString(): String

    companion object {
        private val log: Logger = LoggerFactory.getLogger(BaseTask::class.java)

        const val THREAD_STACK_PAGE = 64
    }

}
