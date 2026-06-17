package com.vortexdbg.linux.struct

import com.sun.jna.Pointer

class IFConf32(p: Pointer?) : IFConf(p) {

    init {
        unpack()
    }

    override fun getIfcuReq(): Long {
        return ifcu_req.toLong()
    }

    @JvmField
    var ifcu_req: Int = 0 // ptr

    override fun getFieldOrder(): List<String> {
        return listOf("ifc_len", "ifcu_req")
    }

}
