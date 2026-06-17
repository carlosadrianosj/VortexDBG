#include <jni.h>
/* Mixed keychain: native mixing + callbacks to Java salt()/hex(). */
JNIEXPORT jstring JNICALL
Java_com_example_keychain_KeyChain_generate(JNIEnv *env, jclass clazz, jstring account) {
    const char *acc = (*env)->GetStringUTFChars(env, account, 0);
    jsize accLen = (*env)->GetStringUTFLength(env, account);

    /* native -> Java: fetch salt */
    jmethodID saltMid = (*env)->GetStaticMethodID(env, clazz, "salt", "()[B");
    jbyteArray saltArr = (jbyteArray)(*env)->CallStaticObjectMethod(env, clazz, saltMid);
    jsize n = (*env)->GetArrayLength(env, saltArr);
    jbyte *salt = (*env)->GetByteArrayElements(env, saltArr, 0);

    jbyte out[64];
    jsize i; int r;
    for (i = 0; i < n; i++) {
        unsigned char a = (i < accLen) ? (unsigned char)acc[i] : (unsigned char)0xA7;
        out[i] = (jbyte)(a ^ (unsigned char)salt[i]);
    }
    for (r = 0; r < 3; r++) {
        for (i = 0; i < n; i++) {
            unsigned char v = (unsigned char)out[i];
            v = (unsigned char)((v << 3) | (v >> 5));               /* rotl 3 */
            v = (unsigned char)(v + (unsigned char)salt[(i + r) % n] + (unsigned char)(r * 7 + 1));
            out[i] = (jbyte)v;
        }
    }
    (*env)->ReleaseByteArrayElements(env, saltArr, salt, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, account, acc);

    /* native -> Java: hex encode */
    jbyteArray derived = (*env)->NewByteArray(env, n);
    (*env)->SetByteArrayRegion(env, derived, 0, n, out);
    jmethodID hexMid = (*env)->GetStaticMethodID(env, clazz, "hex", "([B)Ljava/lang/String;");
    return (jstring)(*env)->CallStaticObjectMethod(env, clazz, hexMid, derived);
}
