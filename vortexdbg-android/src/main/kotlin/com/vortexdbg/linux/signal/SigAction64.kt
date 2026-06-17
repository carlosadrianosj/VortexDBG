package com.vortexdbg.linux.signal

import com.sun.jna.Pointer

class SigAction64(p: Pointer?) : SigAction(p) {

    @JvmField
    var sa_handler: Long = 0
    @JvmField
    var sa_restorer: Long = 0

    override fun getSaHandler(): Long {
        return sa_handler
    }

    override fun setSaHandler(sa_handler: Long) {
        this.sa_handler = sa_handler
    }

    override fun getSaRestorer(): Long {
        return sa_restorer
    }

    override fun setSaRestorer(sa_restorer: Long) {
        this.sa_restorer = sa_restorer
    }

    @JvmField
    var sa_mask: Long = 0
    @JvmField
    var sa_flags: Int = 0

    override fun getMask(): Long {
        return sa_mask
    }

    override fun setMask(mask: Long) {
        this.sa_mask = mask
    }

    override fun getFlags(): Int {
        return sa_flags
    }

    override fun setFlags(flags: Int) {
        this.sa_flags = flags
    }

    override fun getFieldOrder(): List<String> {
        return listOf("sa_handler", "sa_flags", "sa_mask", "sa_restorer")
    }

}
