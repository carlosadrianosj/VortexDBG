#include <jni.h>
#include <string.h>

/*
 * libguard.so - the native half of 02app.
 * Natives are registered via RegisterNatives in JNI_OnLoad (the C functions are NOT named
 * Java_com_example_guard_Guard_*), and they read android.os.Build / java.lang.System through the
 * JNI bridge, so dvm_spoof_env / dvm_mock_jni / dvm_trace_jni have a visible effect.
 */

/* Reads a static String field of com/example/guard/Device through JNI (host-backed, so the
 * un-spoofed baseline works; dvm_spoof_env intercepts by field-name substring). */
static jstring read_device_string(JNIEnv *env, const char *field) {
    jclass dev = (*env)->FindClass(env, "com/example/guard/Device");
    if (dev == NULL) { (*env)->ExceptionClear(env); return (*env)->NewStringUTF(env, "(no-class)"); }
    jfieldID fid = (*env)->GetStaticFieldID(env, dev, field, "Ljava/lang/String;");
    if (fid == NULL) { (*env)->ExceptionClear(env); return (*env)->NewStringUTF(env, "(no-field)"); }
    jstring v = (jstring) (*env)->GetStaticObjectField(env, dev, fid);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return (*env)->NewStringUTF(env, "(err)"); }
    if (v == NULL) return (*env)->NewStringUTF(env, "(null)");
    return v;
}

/* deviceModel(): Device.MODEL */
static jstring g_deviceModel(JNIEnv *env, jclass clazz) {
    return read_device_string(env, "MODEL");
}

/* isEmulator(): true if Device.FINGERPRINT looks like an emulator/unset. */
static jboolean g_isEmulator(JNIEnv *env, jclass clazz) {
    jstring fp = read_device_string(env, "FINGERPRINT");
    const char *s = (*env)->GetStringUTFChars(env, fp, 0);
    int emu = (strstr(s, "generic") != 0) || (strstr(s, "sdk") != 0) ||
              (strstr(s, "emulator") != 0) || (strstr(s, "unknown") != 0) ||
              (strstr(s, "(no-") != 0) || (strstr(s, "(null)") != 0) || (strstr(s, "(err)") != 0);
    (*env)->ReleaseStringUTFChars(env, fp, s);
    return emu ? JNI_TRUE : JNI_FALSE;
}

/* bootToken(): System.currentTimeMillis() / 1000. */
static jlong g_bootToken(JNIEnv *env, jclass clazz) {
    jclass sys = (*env)->FindClass(env, "java/lang/System");
    if (sys == NULL) { (*env)->ExceptionClear(env); return -1; }
    jmethodID mid = (*env)->GetStaticMethodID(env, sys, "currentTimeMillis", "()J");
    if (mid == NULL) { (*env)->ExceptionClear(env); return -1; }
    jlong t = (*env)->CallStaticLongMethod(env, sys, mid);
    return t / 1000;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }
    jclass cls = (*env)->FindClass(env, "com/example/guard/Guard");
    if (cls == NULL) {
        return -1;
    }
    JNINativeMethod methods[3];
    methods[0].name = "deviceModel";
    methods[0].signature = "()Ljava/lang/String;";
    methods[0].fnPtr = (void *) g_deviceModel;
    methods[1].name = "isEmulator";
    methods[1].signature = "()Z";
    methods[1].fnPtr = (void *) g_isEmulator;
    methods[2].name = "bootToken";
    methods[2].signature = "()J";
    methods[2].fnPtr = (void *) g_bootToken;
    if ((*env)->RegisterNatives(env, cls, methods, 3) != 0) {
        return -1;
    }
    return JNI_VERSION_1_6;
}
