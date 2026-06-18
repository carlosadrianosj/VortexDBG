package com.example.guard;

/**
 * A small "anti-tamper" style app for the MCP test suite. Unlike 01app, its native methods are
 * bound via RegisterNatives in JNI_OnLoad (NOT by Java_ symbol name), and the native code reads
 * android.os.Build through the JNI bridge. That makes several MCP tools show a VISIBLE effect:
 *
 *   - dvm_list_native_registrations / dvm_resolve_method / dvm_describe_class -> show real
 *     RegisterNatives bindings (populated by JNI_OnLoad).
 *   - dvm_spoof_env / dvm_mock_jni -> actually change what deviceModel()/isEmulator()/bootToken()
 *     return, because those read Build.MODEL / Build.FINGERPRINT / System.currentTimeMillis().
 *   - dvm_trace_jni -> records the native->Java Build/System callbacks.
 */
public class Guard {

    /** Bound via RegisterNatives. Reads android.os.Build.MODEL through JNI. */
    public static native String deviceModel();

    /** Bound via RegisterNatives. Reads android.os.Build.FINGERPRINT; true if it looks like an emulator. */
    public static native boolean isEmulator();

    /** Bound via RegisterNatives. Derived from System.currentTimeMillis() (seconds). */
    public static native long bootToken();
}
