package com.vortexdbg.linux.android

import com.sun.jna.Pointer

interface SystemPropertyProvider {

    fun getProperty(key: String): String?

    fun __system_property_find(key: String): Pointer? {
        return null
    }

}
