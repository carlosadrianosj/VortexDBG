package com.vortexdbg.unix.struct

import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

class DlInfo64(p: Pointer?) : VortexdbgStructure(p) {

    @JvmField
    var dli_fname: Long = 0 /* Pathname of shared object */
    @JvmField
    var dli_fbase: Long = 0 /* Base address of shared object */
    @JvmField
    var dli_sname: Long = 0 /* Name of nearest symbol */
    @JvmField
    var dli_saddr: Long = 0 /* Address of nearest symbol */

    override fun getFieldOrder(): List<String> {
        return listOf("dli_fname", "dli_fbase", "dli_sname", "dli_saddr")
    }
}
