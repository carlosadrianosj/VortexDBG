package com.vortexdbg.linux.struct

import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

class dl_phdr_info64(p: Pointer?) : VortexdbgStructure(p) {

    @JvmField
    var dlpi_addr: Long = 0 // ptr
    @JvmField
    var dlpi_name: Long = 0 // ptr
    @JvmField
    var dlpi_phdr: Long = 0 // ptr
    @JvmField
    var dlpi_phnum: Short = 0

    override fun getFieldOrder(): List<String> {
        return listOf("dlpi_addr", "dlpi_name", "dlpi_phdr", "dlpi_phnum")
    }

}
