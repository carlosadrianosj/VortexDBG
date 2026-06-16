package com.vortexdbg.e;

/**
 * E (demo) — classe do app com métodos {@code native}. Carregada pelo
 * VortexInstrumentingClassLoader, seus native viram chamadas ao UniDBG (libe.so emulada).
 */
public class EHost {
    public static native int nativeSum(int a, int b);
    public static native byte[] nativeXor(byte[] in, int k);
}
