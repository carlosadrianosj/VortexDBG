#include <jni.h>

/*
 * CAPSTONE (Vortex-DBG / A1) — código NATIVO (emulado pelo UniDBG) que chama
 * CLASSES REAIS do app na JVM host via JNI (ProxyClassFactory + VortexClassLoader).
 * Classe Java alvo (DvmClass): com.vortexdbg.integ.IntegSpike
 */

/* native -> app: org.cf.crypto.XORCrypt.encode(String,String) -> String */
JNIEXPORT jstring JNICALL
Java_com_vortexdbg_integ_IntegSpike_nativeXorEncode(JNIEnv *env, jclass clazz, jstring msg, jstring key) {
    jclass xor = (*env)->FindClass(env, "org/cf/crypto/XORCrypt");
    jmethodID mid = (*env)->GetStaticMethodID(env, xor, "encode",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    return (jstring) (*env)->CallStaticObjectMethod(env, xor, mid, msg, key);
}

/* native -> app -> framework: org.cf.obfuscated.StringHolder.get(int) -> String
 * (StringHolder usa android.util.Base64 — exercita native+app+framework juntos) */
JNIEXPORT jstring JNICALL
Java_com_vortexdbg_integ_IntegSpike_nativeStringGet(JNIEnv *env, jclass clazz, jint idx) {
    jclass sh = (*env)->FindClass(env, "org/cf/obfuscated/StringHolder");
    jmethodID mid = (*env)->GetStaticMethodID(env, sh, "get", "(I)Ljava/lang/String;");
    return (jstring) (*env)->CallStaticObjectMethod(env, sh, mid, idx);
}
