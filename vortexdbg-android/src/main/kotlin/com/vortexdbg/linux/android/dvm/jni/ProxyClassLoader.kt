package com.vortexdbg.linux.android.dvm.jni

class ProxyClassLoader internal constructor(private val classLoader: ClassLoader) {

    private var classNameMapper: ProxyClassMapper? = null

    internal fun setClassNameMapper(classNameMapper: ProxyClassMapper?) {
        this.classNameMapper = classNameMapper
    }

    @Throws(ClassNotFoundException::class)
    internal fun loadClass(name: String): Class<*> {
        val newClass = if (classNameMapper == null) null else classNameMapper!!.map(name)
        if (newClass != null) {
            return newClass
        }
        return classLoader.loadClass(name)
    }

}
