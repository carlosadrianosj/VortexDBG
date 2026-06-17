package com.vortexdbg.arm

import capstone.api.Instruction
import com.vortexdbg.AbstractEmulator
import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.arm.backend.CodeHook
import com.vortexdbg.arm.backend.UnHook
import com.vortexdbg.debugger.FunctionCallListener
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.thread.BaseTask
import com.vortexdbg.thread.RunnableTask
import com.vortexdbg.utils.Inspector
import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class TraceFunctionCall internal constructor(
    @JvmField protected val emulator: Emulator<*>,
    private val listener: FunctionCallListener
) : CodeHook {

    fun pushFunction(callerAddress: Long, functionAddress: Long, returnAddress: Long, args: Array<Number>) {
        val runningTask: RunnableTask = emulator.getThreadDispatcher().getRunningTask()!!
        val call = FunctionCall(callerAddress, functionAddress, returnAddress, args)
        runningTask.pushFunction(emulator, call)
        listener.onDebugPushFunction(emulator, call)
        listener.onCall(emulator, callerAddress, functionAddress)
    }

    private var detectedIllegalState = false

    override fun hook(backend: Backend, address: Long, size: Int, user: Any?) {
        if (detectedIllegalState) {
            return
        }

        val runningTask: RunnableTask = emulator.getThreadDispatcher().getRunningTask()!!
        val call = runningTask.popFunction(emulator, address)
        if (call != null) {
            listener.onDebugPopFunction(emulator, address, call)
            if (call.returnAddress != address) {
                log.warn("Illegal state address={}, call={}", VortexdbgPointer.pointer(emulator, address), call.toReadableString(emulator))
                if (LoggerFactory.getLogger(AbstractEmulator::class.java).isDebugEnabled ||
                    LoggerFactory.getLogger(BaseTask::class.java).isDebugEnabled
                ) {
                    emulator.attach().debug("TraceFunctionCall illegal state: expected return to " + call.toReadableString(emulator))
                }
                detectedIllegalState = true
                return
            } else {
                listener.postCall(emulator, call.callerAddress, call.functionAddress, call.args)
            }
        }
        try {
            var instruction = disassemble(address, size)
            if (instruction != null) {
                if (log.isDebugEnabled) {
                    if (!instruction.getMnemonic().startsWith("bl")) {
                        log.warn(Inspector.inspectString(backend.mem_read(address, size.toLong()), "Invalid " + instruction + ": thumb=" + ARM.isThumb(backend)))
                    }
                }
                onInstruction(instruction)
            } else if (log.isDebugEnabled) {
                val instructions = emulator.disassemble(address, size, 1)
                if (instructions.size != 1) {
                    return
                }
                instruction = instructions[0]
                val mnemonic = instruction.getMnemonic()
                if (emulator.is32Bit()) {
                    if (mnemonic.startsWith("bl") &&
                        !mnemonic.startsWith("ble") &&
                        !mnemonic.startsWith("blt") &&
                        !mnemonic.startsWith("bls") &&
                        !mnemonic.startsWith("blo")
                    ) {
                        log.warn(Inspector.inspectString(backend.mem_read(address, size.toLong()), "Unsupported " + instruction + ": thumb=" + ARM.isThumb(backend)))
                    }
                } else {
                    if (mnemonic.startsWith("bl")) {
                        log.warn(Inspector.inspectString(backend.mem_read(address, size.toLong()), "Unsupported " + instruction + ": thumb=" + ARM.isThumb(backend)))
                    }
                }
            }
        } catch (e: BackendException) {
            throw IllegalStateException(e)
        }
    }

    protected abstract fun disassemble(address: Long, size: Int): Instruction?

    protected abstract fun onInstruction(instruction: Instruction)

    private var unHook: UnHook? = null

    override fun onAttach(unHook: UnHook) {
        this.unHook = unHook
    }

    override fun detach() {
        if (unHook != null) {
            unHook!!.unhook()
            unHook = null
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(TraceFunctionCall::class.java)
    }

}
