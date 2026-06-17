package com.vortexdbg

class StringNumber(@JvmField val value: String) : Number() {

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

    override fun toString(): String {
        return value
    }
}
