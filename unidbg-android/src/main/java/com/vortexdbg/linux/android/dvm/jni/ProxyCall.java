package com.vortexdbg.linux.android.dvm.jni;

import com.vortexdbg.linux.android.dvm.VM;

import java.lang.reflect.InvocationTargetException;

interface ProxyCall {

    Object call(VM vm, Object obj) throws IllegalAccessException, InvocationTargetException, InstantiationException;

}
