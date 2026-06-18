#include <jni.h>

/*
 * libvault.so - the native half of the MCP test app.
 *  - Vault.seal      : STATIC; mixes the inputs with a Java-provided salt and calls back into Java
 *                      (Device.salt() / Device.hex()), so native->Java JNI callbacks happen.
 *  - Vault.transform : INSTANCE; reverses + upper-cases the input string.
 */

/* STATIC native: native mixing + callbacks to Java Device.salt()/hex(). */
JNIEXPORT jstring JNICALL
Java_com_example_mcpdemo_Vault_seal(JNIEnv *env, jclass clazz, jstring account, jstring secret) {
    jclass device = (*env)->FindClass(env, "com/example/mcpdemo/Device");

    jmethodID saltMid = (*env)->GetStaticMethodID(env, device, "salt", "()[B");
    jbyteArray saltArr = (jbyteArray)(*env)->CallStaticObjectMethod(env, device, saltMid);
    jsize n = (*env)->GetArrayLength(env, saltArr);
    jbyte *salt = (*env)->GetByteArrayElements(env, saltArr, 0);

    const char *acc = (*env)->GetStringUTFChars(env, account, 0);
    jsize accLen = (*env)->GetStringUTFLength(env, account);
    const char *sec = (*env)->GetStringUTFChars(env, secret, 0);
    jsize secLen = (*env)->GetStringUTFLength(env, secret);

    jbyte out[64];
    jsize i;
    for (i = 0; i < n; i++) {
        unsigned char a = (i < accLen) ? (unsigned char) acc[i] : 0xA7;
        unsigned char s = (i < secLen) ? (unsigned char) sec[i] : 0x5C;
        out[i] = (jbyte) (a ^ s ^ (unsigned char) salt[i]);
    }

    (*env)->ReleaseByteArrayElements(env, saltArr, salt, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, account, acc);
    (*env)->ReleaseStringUTFChars(env, secret, sec);

    jbyteArray derived = (*env)->NewByteArray(env, n);
    (*env)->SetByteArrayRegion(env, derived, 0, n, out);
    jmethodID hexMid = (*env)->GetStaticMethodID(env, device, "hex", "([B)Ljava/lang/String;");
    return (jstring) (*env)->CallStaticObjectMethod(env, device, hexMid, derived);
}

/* INSTANCE native: reverse + upper-case the input. */
JNIEXPORT jstring JNICALL
Java_com_example_mcpdemo_Vault_transform(JNIEnv *env, jobject thiz, jstring input) {
    const char *in = (*env)->GetStringUTFChars(env, input, 0);
    jsize len = (*env)->GetStringUTFLength(env, input);
    char buf[256];
    jsize i;
    for (i = 0; i < len && i < 255; i++) {
        char c = in[len - 1 - i];
        if (c >= 'a' && c <= 'z') c = (char) (c - 32);
        buf[i] = c;
    }
    buf[i] = 0;
    (*env)->ReleaseStringUTFChars(env, input, in);
    return (*env)->NewStringUTF(env, buf);
}
