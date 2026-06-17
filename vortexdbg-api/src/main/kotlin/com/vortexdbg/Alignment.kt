package com.vortexdbg

class Alignment(@JvmField val address: Long, @JvmField val size: Long) {

    @JvmField
    var begin: Long = 0
    @JvmField
    var dataSize: Long = 0

}
