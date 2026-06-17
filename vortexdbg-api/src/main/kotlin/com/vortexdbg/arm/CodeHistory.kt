package com.vortexdbg.arm

import capstone.api.Instruction
import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.BackendException

class CodeHistory internal constructor(
    @JvmField val address: Long,
    private val size: Int,
    @JvmField val thumb: Boolean
) {

    fun disassemble(emulator: Emulator<*>): Array<Instruction>? {
        if (size <= 1) {
            return null
        }
        val backend: Backend = emulator.getBackend()
        return try {
            val code = backend.mem_read(address, size.toLong())
            emulator.disassemble(address, code, thumb, 0L)
        } catch (e: BackendException) {
            null
        }
    }

}
