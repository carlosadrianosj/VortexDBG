package com.vortexdbg.linux.android.dvm.jni

import com.vortexdbg.linux.android.dvm.BaseVM
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmClassFactory
import com.vortexdbg.linux.android.dvm.Jni

open class ProxyClassFactory(classLoader: ClassLoader) : DvmClassFactory {

    @JvmField
    protected val classLoader: ProxyClassLoader = ProxyClassLoader(classLoader)

    constructor() : this(ProxyClassFactory::class.java.classLoader)

    fun configClassNameMapper(mapper: ProxyClassMapper): ProxyClassFactory {
        classLoader.setClassNameMapper(mapper)
        return this
    }

    @JvmField
    protected var visitor: ProxyDvmObjectVisitor? = null

    fun configObjectVisitor(visitor: ProxyDvmObjectVisitor): ProxyClassFactory {
        this.visitor = visitor
        return this
    }

    override fun createClass(vm: BaseVM, className: String, superClass: DvmClass?, interfaceClasses: kotlin.Array<DvmClass>?): DvmClass? {
        return ProxyDvmClass(vm, className, superClass, interfaceClasses, classLoader, visitor, fallbackJni)
    }

    private var fallbackJni: Jni? = null

    fun setFallbackJni(fallbackJni: Jni?) {
        this.fallbackJni = fallbackJni
    }

}
