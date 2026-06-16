package com.vortexdbg.linux.android.dvm.jni;

import com.vortexdbg.linux.android.dvm.BaseVM;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.Jni;
import com.vortexdbg.linux.android.dvm.JniFunction;

public class ProxyDvmClass extends DvmClass {

    protected ProxyDvmClass(BaseVM vm, String className, DvmClass superClass, DvmClass[] interfaceClasses, ProxyClassLoader classLoader, ProxyDvmObjectVisitor visitor,
                            Jni fallbackJni) {
        super(vm, className, superClass, interfaceClasses, null);

        setJni(createJni(classLoader, visitor, fallbackJni));

        try {
            this.value = classLoader.loadClass(getName());
        } catch (ClassNotFoundException ignored) {
        }
    }

    protected JniFunction createJni(ProxyClassLoader classLoader, ProxyDvmObjectVisitor visitor, Jni fallbackJni) {
        return new ProxyJni(classLoader, visitor, fallbackJni);
    }

}
