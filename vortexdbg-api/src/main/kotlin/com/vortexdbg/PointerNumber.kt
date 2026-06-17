package com.vortexdbg

import com.vortexdbg.pointer.VortexdbgPointer

class PointerNumber(private val value: VortexdbgPointer?) : Number() {

    override fun toInt(): Int {
        return if (this.value == null) 0 else this.value.toUIntPeer().toInt()
    }

    override fun toLong(): Long {
        return if (this.value == null) 0L else this.value.peer
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
        return value.toString()
    }
}
