package com.bytedance.frameworks.core.encrypt;

/**
 * The app class that declares the NATIVE AES entry implemented in libttEncrypt.so
 * (ByteDance's real ttEncrypt). On a device the JNI is wired by System.loadLibrary +
 * JNI_OnLoad; under Vortex-DBG the .so is loaded into the ARM emulator and the call is
 * routed through the DVM/JNI bridge (callStaticJniMethodObject).
 */
public class TTEncryptUtils {
    public static native byte[] ttEncrypt(byte[] data, int length);
}
