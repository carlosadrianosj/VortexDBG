package com.vortexdbg.arm.backend.dynarmic

interface DynarmicCallback {

    fun callSVC(pc: Long, swi: Int)

    /**
     * Returns `false` when the instruction was not handled by the interpreter fallback.
     */
    fun handleInterpreterFallback(pc: Long, num_instructions: Int): Boolean

    fun handleExceptionRaised(pc: Long, exception: Int)

    fun handleMemoryReadFailed(vaddr: Long, size: Int)
    fun handleMemoryWriteFailed(vaddr: Long, size: Int)

}
