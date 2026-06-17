package com.vortexdbg.linux.struct

import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

class dl_phdr_info32(p: Pointer?) : VortexdbgStructure(p) {

    @JvmField
    var dlpi_addr: Int = 0 // ptr
    @JvmField
    var dlpi_name: Int = 0 // ptr
    @JvmField
    var dlpi_phdr: Int = 0 // ptr
    @JvmField
    var dlpi_phnum: Short = 0

    override fun getFieldOrder(): List<String> {
        return listOf("dlpi_addr", "dlpi_name", "dlpi_phdr", "dlpi_phnum")
    }

}
