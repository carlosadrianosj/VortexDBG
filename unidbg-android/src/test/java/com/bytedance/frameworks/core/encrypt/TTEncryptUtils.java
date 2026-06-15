package com.bytedance.frameworks.core.encrypt;

/**
 * I (demo) — classe do app REAL (TikTok/ByteDance) com o método {@code native} de
 * criptografia, cuja implementação está em libttEncrypt.so. Carregada pelo
 * VortexInstrumentingClassLoader, o native é roteado ao UniDBG (E).
 */
public class TTEncryptUtils {
    public static native byte[] ttEncrypt(byte[] data, int length);
}
