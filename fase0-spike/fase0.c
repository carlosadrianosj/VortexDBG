#include <jni.h>
#include <string.h>

/*
 * FASE 0 — prova mínima da ponte JNI (Vortex-DBG / arquitetura A1).
 * Funções name-exported (Java_<pkg>_Fase0Spike_<m>) — UniDBG resolve por nome.
 * Classe Java alvo: com.vortexdbg.fase0.Fase0Spike
 */

/* 1) WRITE-BACK: muta o byte[] in-place (XOR 0x5A) e devolve via mode 0. */
JNIEXPORT void JNICALL
Java_com_vortexdbg_fase0_Fase0Spike_mutate(JNIEnv *env, jclass clazz, jbyteArray arr) {
    jsize len = (*env)->GetArrayLength(env, arr);
    jbyte *buf = (*env)->GetByteArrayElements(env, arr, NULL);
    for (jsize i = 0; i < len; i++) {
        buf[i] = (jbyte) (buf[i] ^ 0x5A);
    }
    /* mode 0 => copia de volta para o array Java + libera o buffer nativo */
    (*env)->ReleaseByteArrayElements(env, arr, buf, 0);
}

/* 2) IDENTIDADE: IsSameObject através da ponte. */
JNIEXPORT jboolean JNICALL
Java_com_vortexdbg_fase0_Fase0Spike_isSame(JNIEnv *env, jclass clazz, jobject a, jobject b) {
    return (*env)->IsSameObject(env, a, b);
}

/* 3) EXCEÇÃO: lança RuntimeException p/ testar propagação host<->JNI. */
JNIEXPORT void JNICALL
Java_com_vortexdbg_fase0_Fase0Spike_doThrow(JNIEnv *env, jclass clazz) {
    jclass ex = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, ex, "fase0 native throw");
}
