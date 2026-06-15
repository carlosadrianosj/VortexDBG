package com.vortexdbg.ios.objc.processor;

public interface ObjcClass {

    String getName();

    ObjcClass getMeta();

    ObjcMethod[] getMethods();

}
