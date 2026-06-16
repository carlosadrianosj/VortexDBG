package com.vortexdbg.arm.backend.dynarmic

interface DynarmicCallback {

    fun callSVC(pc: Long, swi: Int)

    /**
     * 返回<code>false</code>表示未处理的指令
     */
    fun handleInterpreterFallback(pc: Long, num_instructions: Int): Boolean

    fun handleExceptionRaised(pc: Long, exception: Int)

    fun handleMemoryReadFailed(vaddr: Long, size: Int)
    fun handleMemoryWriteFailed(vaddr: Long, size: Int)

}
