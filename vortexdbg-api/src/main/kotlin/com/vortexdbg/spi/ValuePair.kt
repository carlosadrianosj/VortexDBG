package com.vortexdbg.spi

interface ValuePair {

    fun set(key: String, value: Any)
    fun <T> get(key: String): T

}
