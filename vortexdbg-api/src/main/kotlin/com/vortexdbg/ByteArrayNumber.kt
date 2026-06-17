package com.vortexdbg

class ByteArrayNumber(@JvmField val value: ByteArray) : Number() {

    override fun toInt(): Int {
        throw AbstractMethodError()
    }

    override fun toLong(): Long {
        throw AbstractMethodError()
    }

    override fun toFloat(): Float {
        throw AbstractMethodError()
    }

    override fun toDouble(): Double {
        throw AbstractMethodError()
    }

    override fun toByte(): Byte {
        throw AbstractMethodError()
    }

    override fun toShort(): Short {
        throw AbstractMethodError()
    }
}
