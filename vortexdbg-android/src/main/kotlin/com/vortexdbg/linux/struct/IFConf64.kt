package com.vortexdbg.linux.struct

import com.sun.jna.Pointer

class IFConf64(p: Pointer?) : IFConf(p) {

    init {
        unpack()
    }

    override fun getIfcuReq(): Long {
        return ifcu_req
    }

    @JvmField
    var ifcu_req: Long = 0 // ptr

    override fun getFieldOrder(): List<String> {
        return listOf("ifc_len", "ifcu_req")
    }

}
