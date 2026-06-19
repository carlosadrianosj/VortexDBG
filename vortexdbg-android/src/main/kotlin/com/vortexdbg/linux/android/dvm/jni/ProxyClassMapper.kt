package com.vortexdbg.linux.android.dvm.jni

interface ProxyClassMapper {

    /**
     * Redirects a class name requested by the emulated app to a host class.
     *
     * Return null to fall back to the default class loader; non-null lets
     * callers substitute their own implementation for the named class.
     */
    fun map(className: String): Class<*>?

}
