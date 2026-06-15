package com.vortexdbg.ios.classdump;

import com.vortexdbg.hook.IHook;

public interface IClassDumper extends IHook {

    String dumpClass(String className);

    void searchClass(String keywords);

}
