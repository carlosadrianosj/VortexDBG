package com.vortexdbg.i;

import com.vortexdbg.app.VortexInstrumentingClassLoader;
import com.vortexdbg.app.VortexNativeDispatch;
import com.vortexdbg.app.VortexSession;
import com.vortexdbg.linux.android.dvm.DvmObject;
import com.vortexdbg.linux.android.dvm.array.ByteArray;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * I (Vortex-DBG / A1) — validação em código de PRODUÇÃO REAL.
 *
 * Usa o libttEncrypt.so real (criptografia do TikTok/ByteDance) e valida o caminho E
 * (host-Java → native emulado) contra a chamada direta do UniDBG (oráculo):
 *   - oráculo: DvmClass.callStaticJniMethodObject("ttEncrypt([BI)[B", ...)
 *   - E-path:  TTEncryptUtils.ttEncrypt(...) no host, instrumentado e roteado ao UniDBG
 * Ambos devem produzir o MESMO ciphertext real.
 */
public class IDemo {

    private static final String CN = "com.bytedance.frameworks.core.encrypt.TTEncryptUtils";

    public static void main(String[] args) throws Exception {
        File so = new File("unidbg-android/src/test/resources/example_binaries/libttEncrypt.so");

        try (VortexSession s = VortexSession.builder()
                .arch64(false)
                .processName("com.qidian.dldl.official")
                .sdk(23)
                .nativeLib(so)
                .callJniOnLoad(true)
                .open()) {

            System.out.println("=== I — .so de produção real (TikTok libttEncrypt), backend=" + s.emulator().getBackend() + " ===");

            byte[] input = new byte[16];

            // ORÁCULO: chamada direta UniDBG
            DvmObject<?> oracleObj = s.resolveNativeClass(CN.replace('.', '/'))
                    .callStaticJniMethodObject(s.emulator(), "ttEncrypt([BI)[B",
                            new ByteArray(s.vm(), input), input.length);
            byte[] oracle = (byte[]) oracleObj.getValue();
            System.out.println("[oráculo]  ttEncrypt(16x00) = " + hex(oracle));

            // E-PATH: método native do host, instrumentado
            VortexNativeDispatch.setSession(s);
            VortexInstrumentingClassLoader il =
                    new VortexInstrumentingClassLoader(IDemo.class.getClassLoader(), "com.bytedance.");
            Class<?> tt = il.loadClass(CN);
            Method m = tt.getDeclaredMethod("ttEncrypt", byte[].class, int.class);
            byte[] viaE = (byte[]) m.invoke(null, new byte[16], 16);
            System.out.println("[E-path]   TTEncryptUtils.ttEncrypt(16x00) = " + hex(viaE));

            boolean ok = oracle != null && oracle.length > 0 && Arrays.equals(oracle, viaE);
            System.out.println("RESULTADO I: " + (ok
                    ? "OK — E validado em .so de PRODUÇÃO REAL (oráculo == E-path)"
                    : "FALHOU"));
            VortexNativeDispatch.clearSession();
        }
    }

    private static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
