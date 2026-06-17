package com.vortexdbg.linux.android.dvm

/**
 * @see XxHash32
 */
interface HashFunction {

    fun hash(className: String): Int

}
