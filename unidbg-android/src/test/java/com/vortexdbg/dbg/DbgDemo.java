package com.vortexdbg.dbg;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.Module;
import com.vortexdbg.app.VortexDebugger;
import com.vortexdbg.arm.backend.BackendFactory;
import com.vortexdbg.arm.backend.Unicorn2Factory;
import com.vortexdbg.linux.android.AndroidARM64Emulator;
import com.vortexdbg.linux.android.AndroidResolver;
import com.vortexdbg.linux.android.dvm.DalvikModule;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.VM;
import com.vortexdbg.memory.Memory;

import java.io.File;
import java.util.Collections;

/**
 * G — demo do debugger dual-layer.
 *
 * Fluxo: host chama compute(21) → o NATIVO (libdbg.so) roda compute → faz um callback
 * JNI DbgHost.onStep(210) → retorna 42. O VortexDebugger observa AS DUAS camadas:
 *   - breakpoint NATIVO em `compute` (PC, registradores X0/X2, backtrace ARM);
 *   - o cruzamento JNI native→java em `onStep`, com a pilha unificada.
 */
public class DbgDemo {

    public static void main(String[] args) {
        AndroidEmulator emulator = new AndroidARM64Emulator("dbg",
                new File("target/rootfs"),
                Collections.<BackendFactory>singletonList(new Unicorn2Factory(true))) {};
        try {
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(23));

            VM vm = emulator.createDalvikVM();
            vm.setVerbose(false);

            VortexDebugger dbg = new VortexDebugger(emulator);

            // camada JAVA / fronteira JNI: observa os cruzamentos native->java
            DbgHost jni = new DbgHost();
            vm.setJni(dbg.instrument(jni, (signature, rawArgs) -> {
                System.out.println("  [JAVA CROSS]  native -> java   " + signature);
                System.out.println("      pilha unificada: nativo " + dbg.nativeStack(6) + "  ->  [java " + signature + "]");
            }));

            DalvikModule dm = vm.loadLibrary(new File("dbg-spike/libdbg.so"), false);
            Module module = dm.getModule();
            DvmClass cls = vm.resolveClass("com/vortexdbg/dbg/DbgHost");

            // camada NATIVA: breakpoint na função compute
            dbg.breakNative(module, "Java_com_vortexdbg_dbg_DbgHost_compute", ctx -> {
                System.out.println("  [NATIVE BREAK] compute @ 0x" + Long.toHexString(ctx.pc())
                        + "   X0(env)=0x" + Long.toHexString(ctx.arg(0)) + "   X2(x)=" + ctx.arg(2));
                System.out.println("      backtrace nativo: " + ctx.nativeStack(6));
            });

            System.out.println("=== G — debugger dual-layer: chamando compute(21) ===");
            int r = cls.callStaticJniMethodInt(emulator, "compute(I)I", 21);
            System.out.println("compute(21) = " + r + "   (esperado 42)");
            System.out.println("RESULTADO G: " + (r == 42
                    ? "OK — breakpoint nativo + cruzamento JNI observados nas DUAS camadas"
                    : "FALHOU"));
        } finally {
            try { emulator.close(); } catch (Exception ignored) {}
        }
    }
}
