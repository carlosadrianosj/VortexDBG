package com.vortexdbg.hook;

public interface InvocationContext {

    void push(Object... objs);

    <T> T pop();

}
