package com.vortexdbg.linux.struct

import com.sun.jna.Pointer

class IFReq64 internal constructor(p: Pointer?) : IFReq(p) {

    @JvmField
    var ifr_ifru = ByteArray(IFNAMSIZ + 8)

    override fun getFieldOrder(): List<String> {
        return listOf("ifrn_name", "ifr_ifru")
    }

}
