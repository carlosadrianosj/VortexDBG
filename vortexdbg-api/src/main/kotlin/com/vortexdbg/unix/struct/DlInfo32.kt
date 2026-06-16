package com.vortexdbg.unix.struct

import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

class DlInfo32(p: Pointer?) : VortexdbgStructure(p) {

    @JvmField
    var dli_fname: Int = 0 /* Pathname of shared object */
    @JvmField
    var dli_fbase: Int = 0 /* Base address of shared object */
    @JvmField
    var dli_sname: Int = 0 /* Name of nearest symbol */
    @JvmField
    var dli_saddr: Int = 0 /* Address of nearest symbol */

    override fun getFieldOrder(): List<String> {
        return listOf("dli_fname", "dli_fbase", "dli_sname", "dli_saddr")
    }
}
