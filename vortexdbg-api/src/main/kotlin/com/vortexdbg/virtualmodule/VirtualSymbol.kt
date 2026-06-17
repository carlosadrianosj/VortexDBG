package com.vortexdbg.virtualmodule

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.Symbol

class VirtualSymbol(name: String, private val module: Module?, private val address: Long) : Symbol(name) {

    override fun call(emulator: Emulator<*>, vararg args: Any?): Number {
        return Module.emulateFunction(emulator, address, *args)
    }

    override fun getAddress(): Long {
        return address
    }

    override fun getValue(): Long {
        return address - module!!.base
    }

    override fun isUndef(): Boolean {
        return false
    }

    override fun getModuleName(): String {
        return module!!.name
    }

}
