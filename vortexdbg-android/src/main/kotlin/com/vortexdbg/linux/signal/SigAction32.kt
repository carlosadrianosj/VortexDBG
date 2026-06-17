package com.vortexdbg.linux.signal

import com.sun.jna.Pointer

class SigAction32(p: Pointer?) : SigAction(p) {

    @JvmField
    var sa_handler: Int = 0 // ptr
    @JvmField
    var sa_restorer: Int = 0 // ptr

    override fun getSaHandler(): Long {
        return sa_handler.toLong()
    }

    override fun setSaHandler(sa_handler: Long) {
        this.sa_handler = sa_handler.toInt()
    }

    override fun getSaRestorer(): Long {
        return sa_restorer.toLong()
    }

    override fun setSaRestorer(sa_restorer: Long) {
        this.sa_restorer = sa_restorer.toInt()
    }

    @JvmField
    var sa_mask: Int = 0
    @JvmField
    var sa_flags: Int = 0

    override fun getMask(): Long {
        return sa_mask.toLong()
    }

    override fun setMask(mask: Long) {
        this.sa_mask = mask.toInt()
    }

    override fun getFlags(): Int {
        return sa_flags
    }

    override fun setFlags(flags: Int) {
        this.sa_flags = flags
    }

    override fun getFieldOrder(): List<String> {
        return listOf("sa_handler", "sa_mask", "sa_flags", "sa_restorer")
    }

}
