package com.example.faulty;

/**
 * A native target that raises a real Java exception through JNI (ThrowNew), so dvm_pending_exception
 * shows an ACTUAL pending exception (not "none"). risky("ok") succeeds; anything else makes the
 * native throw java.lang.IllegalArgumentException, which the VM keeps as a pending exception.
 */
public class Faulty {

    /** Returns "accepted" for "ok"; otherwise the native throws IllegalArgumentException. */
    public static native String risky(String input);
}
