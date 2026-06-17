package com.vortexdbg.linux.android.dvm.jni

interface ProxyClassMapper {

    /**
     * map class name to new class
     */
    fun map(className: String): Class<*>?

}
