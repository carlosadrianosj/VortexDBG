package com.vortexdbg.arm

import com.vortexdbg.LongJumpException

class NestedRun private constructor(@JvmField val pc: Long) : LongJumpException() {

    companion object {
        /**
         * Requests that emulation resume at [pc] instead of returning from the hook;
         * the caller is responsible for setting up the call context (arguments, LR).
         */
        @JvmStatic
        fun runToFunction(pc: Long): NestedRun {
            return NestedRun(pc)
        }
    }

}
