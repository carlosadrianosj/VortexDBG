package com.vortexdbg.linux.android.dvm.jni

import java.lang.reflect.Member

interface ProxyDvmObjectVisitor {

    fun onProxyVisit(member: Member, obj: Any?, args: Array<Any?>?)

    @Suppress("unused")
    fun <T> postProxyVisit(member: Member, obj: Any?, args: Array<Any?>?, result: T): T {
        return result
    }

}
