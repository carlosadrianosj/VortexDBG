package com.example.guard;

/**
 * Stand-in for android.os.Build. The emulator's host JVM has no android.os.Build, so this app
 * carries its own device-identity fields (with emulator-ish defaults) that the native code reads
 * through JNI. Because dvm_spoof_env matches on the field-name substring (MODEL / FINGERPRINT),
 * spoofing these has a real, visible effect on what the native methods return — while the
 * un-spoofed baseline still works (the fields are host-backed).
 */
public class Device {
    public static String MODEL = "sdk_gphone64_arm64";
    public static String FINGERPRINT = "generic/sdk_gphone64_arm64:13/UE1A.230829.036/emulator:userdebug/test-keys";
    public static String MANUFACTURER = "Google";
}
