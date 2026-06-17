package com.vortexdbg.linux.android.dvm

import com.vortexdbg.Emulator
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.pointer.VortexdbgPointer

abstract class ArmVarArg protected constructor(protected val emulator: Emulator<*>, vm: BaseVM, method: DvmMethod) : VarArg(vm, method) {

    protected fun getArg(index: Int): VortexdbgPointer? {
        return emulator.getContext<RegisterContext>().getPointerArg(REG_OFFSET + index)
    }

    protected fun getInt(index: Int): Int {
        val ptr = getArg(index)
        return if (ptr == null) 0 else ptr.toIntPeer()
    }

    companion object {
        @JvmStatic
        fun create(emulator: Emulator<*>, vm: BaseVM, method: DvmMethod): VarArg {
            return if (emulator.is64Bit()) ArmVarArg64(emulator, vm, method) else ArmVarArg32(emulator, vm, method)
        }

        private const val REG_OFFSET = 3
    }
}
