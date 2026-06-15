package com.vortexdbg.linux.android.dvm.jni;

import com.vortexdbg.linux.android.dvm.BaseVM;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.DvmClassFactory;
import com.vortexdbg.linux.android.dvm.Jni;

public class ProxyClassFactory implements DvmClassFactory {

    protected final ProxyClassLoader classLoader;

    public ProxyClassFactory() {
        this(ProxyClassFactory.class.getClassLoader());
    }

    public ProxyClassFactory(ClassLoader classLoader) {
        this.classLoader = new ProxyClassLoader(classLoader);
    }

    public ProxyClassFactory configClassNameMapper(ProxyClassMapper mapper) {
        classLoader.setClassNameMapper(mapper);
        return this;
    }

    protected ProxyDvmObjectVisitor visitor;

    public ProxyClassFactory configObjectVisitor(ProxyDvmObjectVisitor visitor) {
        this.visitor = visitor;
        return this;
    }

    @Override
    public DvmClass createClass(BaseVM vm, String className, DvmClass superClass, DvmClass[] interfaceClasses) {
        return new ProxyDvmClass(vm, className, superClass, interfaceClasses, classLoader, visitor, fallbackJni);
    }

    private Jni fallbackJni;

    public void setFallbackJni(Jni fallbackJni) {
        this.fallbackJni = fallbackJni;
    }

}
