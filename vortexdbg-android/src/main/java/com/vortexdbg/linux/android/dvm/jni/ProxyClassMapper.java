package com.vortexdbg.linux.android.dvm.jni;

public interface ProxyClassMapper {

    /**
     * map class name to new class
     */
    Class<?> map(String className);

}
