package com.vortexdbg.linux.android.dvm.jni

import com.vortexdbg.linux.android.dvm.BaseVM
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.Jni
import com.vortexdbg.linux.android.dvm.JniFunction

open class ProxyDvmClass internal constructor(
    vm: BaseVM,
    className: String,
    superClass: DvmClass?,
    interfaceClasses: kotlin.Array<DvmClass>?,
    classLoader: ProxyClassLoader,
    visitor: ProxyDvmObjectVisitor?,
    fallbackJni: Jni?
) : DvmClass(vm, className, superClass, interfaceClasses, null) {

    init {
        setJni(createJni(classLoader, visitor, fallbackJni))

        try {
            this.value = classLoader.loadClass(getName())
        } catch (ignored: ClassNotFoundException) {
        }
    }

    protected open fun createJni(classLoader: ProxyClassLoader, visitor: ProxyDvmObjectVisitor?, fallbackJni: Jni?): JniFunction {
        return ProxyJni(classLoader, visitor, fallbackJni)
    }

}
