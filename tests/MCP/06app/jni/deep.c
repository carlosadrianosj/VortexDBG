#include <jni.h>

/*
 * libdeep.so - a deep, non-inlined native call chain so get_callstack produces a real multi-frame
 * backtrace: compute -> deep_level1 -> deep_level2 -> deep_level3. Break inside deep_level3.
 */

__attribute__((noinline)) int deep_level3(int x) {
    volatile int y = x * 3 + 1;
    return y;
}

__attribute__((noinline)) int deep_level2(int x) {
    volatile int y = deep_level3(x) + 2;
    return y;
}

__attribute__((noinline)) int deep_level1(int x) {
    volatile int y = deep_level2(x) + 3;
    return y;
}

JNIEXPORT jint JNICALL
Java_com_example_deep_Deep_compute(JNIEnv *env, jclass clazz, jint n) {
    return deep_level1(n);
}
