package com.vortexdbg.linux.struct

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

import java.nio.charset.StandardCharsets
import java.util.Arrays

abstract class IFReq internal constructor(p: Pointer?) : VortexdbgStructure(p) {

    fun getAddrPointer(): Pointer {
        return getPointer().share(IFNAMSIZ.toLong())
    }

    fun setName(name: String) {
        val data = name.toByteArray(StandardCharsets.UTF_8)
        if (data.size >= IFNAMSIZ) {
            throw IllegalStateException("name=$name")
        }
        ifrn_name = Arrays.copyOf(data, IFNAMSIZ)
    }

    @JvmField
    var ifrn_name = ByteArray(IFNAMSIZ)

    companion object {
        internal const val IFNAMSIZ = 16

        @JvmStatic
        fun createIFReq(emulator: Emulator<*>, pointer: Pointer?): IFReq {
            return if (emulator.is64Bit()) IFReq64(pointer) else IFReq32(pointer)
        }
    }

}
