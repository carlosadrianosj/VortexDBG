#include <jni.h>
/* E (Vortex-DBG) — funções nativas PURAS p/ demo host-Java -> native emulado.
 * Classe Java alvo: com.vortexdbg.e.EHost */
JNIEXPORT jint JNICALL
Java_com_vortexdbg_e_EHost_nativeSum(JNIEnv *env, jclass c, jint a, jint b) {
    return a + b;
}
JNIEXPORT jbyteArray JNICALL
Java_com_vortexdbg_e_EHost_nativeXor(JNIEnv *env, jclass c, jbyteArray in, jint k) {
    jsize n = (*env)->GetArrayLength(env, in);
    jbyte *buf = (*env)->GetByteArrayElements(env, in, NULL);
    jbyteArray out = (*env)->NewByteArray(env, n);
    jbyte *obuf = (*env)->GetByteArrayElements(env, out, NULL);
    for (jsize i = 0; i < n; i++) obuf[i] = (jbyte)(buf[i] ^ (jbyte)k);
    (*env)->ReleaseByteArrayElements(env, out, obuf, 0);
    (*env)->ReleaseByteArrayElements(env, in, buf, JNI_ABORT);
    return out;
}
