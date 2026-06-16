package com.vortexdbg.e;

import com.vortexdbg.app.VortexInstrumentingClassLoader;
import com.vortexdbg.app.VortexNativeDispatch;
import com.vortexdbg.app.VortexSession;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * E (Vortex-DBG / A1) — demo da "outra metade" da fusão: Java(host) → native(emulado).
 *
 * EHost tem métodos {@code native}. Carregada pelo VortexInstrumentingClassLoader, seus
 * native são reescritos para rotear ao UniDBG (libe.so emulada). Chamamos os métodos no
 * HOST e o resultado vem da execução nativa emulada.
 */
public class EDemo {

    public static void main(String[] args) throws Exception {
        int pass = 0, fail = 0;
        try (VortexSession s = VortexSession.builder()
                .nativeLib(new File("tests/e-spike/libe.so"))
                .open()) {

            VortexNativeDispatch.setSession(s);
            System.out.println("=== E — host-Java -> native emulado (backend=" + s.emulator().getBackend() + ") ===");

            VortexInstrumentingClassLoader il =
                    new VortexInstrumentingClassLoader(EDemo.class.getClassLoader(), "com.vortexdbg.e.");
            Class<?> eh = il.loadClass("com.vortexdbg.e.EHost");

            // T1: nativeSum(20,22) -> 42, via dispatch para a .so emulada
            Method sum = eh.getDeclaredMethod("nativeSum", int.class, int.class);
            Object r1 = sum.invoke(null, 20, 22);
            boolean ok1 = Integer.valueOf(42).equals(r1);
            System.out.println("[T1] EHost.nativeSum(20,22) = " + r1 + "  " + (ok1 ? "OK" : "FALHOU"));
            if (ok1) pass++; else fail++;

            // T2: nativeXor([1,2,3], 0x5A) -> [^0x5A...], retorno de array da .so emulada
            Method xor = eh.getDeclaredMethod("nativeXor", byte[].class, int.class);
            byte[] out = (byte[]) xor.invoke(null, new byte[]{1, 2, 3}, 0x5A);
            boolean ok2 = out != null && out.length == 3
                    && out[0] == (byte) (1 ^ 0x5A) && out[1] == (byte) (2 ^ 0x5A) && out[2] == (byte) (3 ^ 0x5A);
            System.out.println("[T2] EHost.nativeXor([1,2,3],0x5A) = " + Arrays.toString(out) + "  " + (ok2 ? "OK" : "FALHOU"));
            if (ok2) pass++; else fail++;

            System.out.println("RESULTADO E: pass=" + pass + " fail=" + fail);
        } finally {
            VortexNativeDispatch.clearSession();
        }
    }
}
