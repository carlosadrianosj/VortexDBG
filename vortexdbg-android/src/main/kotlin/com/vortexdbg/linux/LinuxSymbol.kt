package com.vortexdbg.linux

import com.vortexdbg.Emulator
import com.vortexdbg.Symbol
import net.fornwall.jelf.ElfSymbol

import java.io.IOException

class LinuxSymbol @Throws(IOException::class) constructor(
    private val module: LinuxModule,
    private val elfSymbol: ElfSymbol
) : Symbol(elfSymbol.getName()!!) {

    override fun isUndef(): Boolean {
        return elfSymbol.isUndef()
    }

    override fun call(emulator: Emulator<*>, vararg args: Any?): Number {
        return module.callFunction(emulator, getValue(), *args)
    }

    override fun getAddress(): Long {
        return module.base + getValue()
    }

    override fun getValue(): Long {
        return elfSymbol.value
    }

    override fun getModuleName(): String {
        return module.name
    }
}
