package com.vortexdbg.debugger

import com.vortexdbg.Emulator
import com.vortexdbg.arm.FunctionCall

abstract class FunctionCallListener {

    abstract fun onCall(emulator: Emulator<*>, callerAddress: Long, functionAddress: Long)

    abstract fun postCall(emulator: Emulator<*>, callerAddress: Long, functionAddress: Long, args: Array<Number>)

    open fun onDebugPushFunction(emulator: Emulator<*>, call: FunctionCall) {
    }

    open fun onDebugPopFunction(emulator: Emulator<*>, address: Long, call: FunctionCall) {
    }

}
