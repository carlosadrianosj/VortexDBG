package com.vortexdbg.fase0;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.arm.backend.BackendFactory;
import com.vortexdbg.arm.backend.Unicorn2Factory;
import com.vortexdbg.linux.android.AndroidARM64Emulator;
import com.vortexdbg.linux.android.AndroidResolver;
import com.vortexdbg.linux.android.dvm.AbstractJni;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.StringObject;
import com.vortexdbg.linux.android.dvm.VM;
import com.vortexdbg.linux.android.dvm.array.ByteArray;
import com.vortexdbg.memory.Memory;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

/**
 * FASE 0 — de-risk da ponte JNI (Vortex-DBG / arquitetura A1).
 *
 * Prova empírica de 3 propriedades da ponte native <-> Java host:
 *   T1) WRITE-BACK: native muta um byte[] (GetByteArrayElements + Release mode 0)
 *       e o lado Java vê a mutação.
 *   T2) IDENTIDADE: IsSameObject através da ponte (mesmo handle => true; outro => false).
 *   T3) EXCEÇÃO: ThrowNew do native propaga para o lado Java.
 *
 * Backend: Unicorn2 (sem entitlement). .so: fase0-spike/libfase0.so (arm64).
 */
public class Fase0Spike extends AbstractJni {

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    public static void main(String[] args) {
        int pass = 0, fail = 0;
        AndroidEmulator emulator = new AndroidARM64Emulator("fase0",
                new File("target/rootfs"),
                Collections.<BackendFactory>singletonList(new Unicorn2Factory(true))) {};
        try {
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(23));
            VM vm = emulator.createDalvikVM();
            vm.setJni(new Fase0Spike());
            vm.setVerbose(false);
            vm.loadLibrary(new File("fase0-spike/libfase0.so"), false);
            DvmClass cls = vm.resolveClass("com/vortexdbg/fase0/Fase0Spike");

            System.out.println("================ FASE 0 — ponte JNI ================");

            // ---- T1: WRITE-BACK ----
            byte[] original = {0x00, 0x11, 0x22, 0x33};
            byte[] snapshot = original.clone();
            byte[] expected = new byte[original.length];
            for (int i = 0; i < original.length; i++) expected[i] = (byte) (original[i] ^ 0x5A);

            ByteArray ba = new ByteArray(vm, original);
            cls.callStaticJniMethod(emulator, "mutate([B)V", ba);
            byte[] after = ba.getValue();

            boolean wroteBack = Arrays.equals(after, expected);
            boolean originalMutatedInPlace = !Arrays.equals(original, snapshot);
            System.out.println("[T1 write-back]");
            System.out.println("   antes      = " + hex(snapshot));
            System.out.println("   esperado   = " + hex(expected) + "  (XOR 0x5A)");
            System.out.println("   getValue() = " + hex(after));
            System.out.println("   -> write-back via DvmObject.getValue(): " + (wroteBack ? "OK" : "FALHOU"));
            System.out.println("   -> instancia byte[] original mutada in-place? " + originalMutatedInPlace
                    + " (after==original ref: " + (after == original) + ")");
            if (wroteBack) pass++; else fail++;

            // ---- T2: IDENTIDADE (IsSameObject) ----
            StringObject s1 = new StringObject(vm, "alpha");
            StringObject s2 = new StringObject(vm, "beta");
            boolean sameAA = cls.callStaticJniMethodBoolean(emulator,
                    "isSame(Ljava/lang/Object;Ljava/lang/Object;)Z", s1, s1);
            boolean sameAB = cls.callStaticJniMethodBoolean(emulator,
                    "isSame(Ljava/lang/Object;Ljava/lang/Object;)Z", s1, s2);
            System.out.println("[T2 identidade] isSame(s1,s1)=" + sameAA + " (esperado true), "
                    + "isSame(s1,s2)=" + sameAB + " (esperado false)");
            if (sameAA && !sameAB) pass++; else fail++;

            // ---- T3: EXCECAO ----
            boolean threw = false;
            String detail = "";
            try {
                cls.callStaticJniMethod(emulator, "doThrow()V");
            } catch (Throwable t) {
                threw = true;
                detail = t.getClass().getName() + ": " + t.getMessage();
            }
            System.out.println("[T3 excecao] native ThrowNew propagou? " + threw + "  " + detail);
            if (threw) pass++; else fail++;

            System.out.println("====================================================");
            System.out.println("RESULTADO FASE 0: pass=" + pass + " fail=" + fail);
        } finally {
            try { emulator.close(); } catch (Exception ignored) {}
        }
    }
}
