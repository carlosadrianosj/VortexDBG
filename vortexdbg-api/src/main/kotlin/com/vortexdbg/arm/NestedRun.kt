package com.vortexdbg.arm

import com.vortexdbg.LongJumpException

class NestedRun private constructor(@JvmField val pc: Long) : LongJumpException() {

    companion object {
        /**
         * need custom fix call context.
         */
        @JvmStatic
        fun runToFunction(pc: Long): NestedRun {
            return NestedRun(pc)
        }
    }

}
