#include <jni.h>
/* G (Vortex-DBG) — .so de demo do debugger dual-layer.
 * compute(x): faz um callback Java DbgHost.onStep(x*10) e retorna x*2.
 * Classe Java alvo: com.vortexdbg.dbg.DbgHost */
JNIEXPORT jint JNICALL
Java_com_vortexdbg_dbg_DbgHost_compute(JNIEnv *env, jclass clazz, jint x) {
    jclass c = (*env)->FindClass(env, "com/vortexdbg/dbg/DbgHost");
    jmethodID m = (*env)->GetStaticMethodID(env, c, "onStep", "(I)V");
    (*env)->CallStaticVoidMethod(env, c, m, x * 10);
    return x * 2;
}
