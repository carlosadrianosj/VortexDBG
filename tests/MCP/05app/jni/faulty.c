#include <jni.h>
#include <string.h>
#include <stdio.h>

/*
 * libfaulty.so - raises a real Java exception through JNI. ThrowNew leaves the exception PENDING on
 * the VM; it is cleared when the JNI call returns (deleteLocalRefs). So to observe it with
 * dvm_pending_exception you break at faulty_after_throw (called right after ThrowNew, while the
 * exception is still pending) and query there.
 */

/* Exported, non-inlined marker so an MCP client can breakpoint right after the throw. */
volatile int g_faulty_marker = 0;

__attribute__((noinline)) void faulty_after_throw(void) {
    g_faulty_marker++;
}

JNIEXPORT jstring JNICALL
Java_com_example_faulty_Faulty_risky(JNIEnv *env, jclass clazz, jstring input) {
    const char *s = (*env)->GetStringUTFChars(env, input, 0);
    if (strcmp(s, "ok") == 0) {
        (*env)->ReleaseStringUTFChars(env, input, s);
        return (*env)->NewStringUTF(env, "accepted");
    }
    char msg[160];
    snprintf(msg, sizeof(msg), "rejected input: %s", s);
    (*env)->ReleaseStringUTFChars(env, input, s);

    jclass ex = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    (*env)->ThrowNew(env, ex, msg);
    faulty_after_throw(); /* breakpoint here: the JNI exception is now pending */
    return NULL;
}
