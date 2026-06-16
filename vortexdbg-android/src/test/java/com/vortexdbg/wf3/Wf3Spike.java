package com.vortexdbg.wf3;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.arm.backend.BackendFactory;
import com.vortexdbg.arm.backend.Unicorn2Factory;
import com.vortexdbg.linux.android.AndroidARM64Emulator;
import com.vortexdbg.linux.android.AndroidResolver;
import com.vortexdbg.linux.android.dvm.AbstractJni;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.VM;
import com.vortexdbg.linux.android.dvm.VortexJniException;
import com.vortexdbg.linux.android.dvm.array.ByteArray;
import com.vortexdbg.memory.Memory;

import java.io.File;
import java.util.Collections;

/**
 * WF3 — Endurecimento da ponte JNI: propagação de exceção native-&gt;host.
 *
 * A FASE 0 mostrou que ThrowNew deixava a exceção PENDENTE em BaseVM.throwable mas
 * NÃO a propagava ao host. O WF3 adicionou {@link VM#setExceptionPropagation(boolean)}:
 * ao retornar de uma chamada JNI com exceção pendente, o Vortex lança
 * {@link VortexJniException} no host. Reusa o tests/fase0-spike/libfase0.so.
 */
public class Wf3Spike extends AbstractJni {

    public static void main(String[] args) {
        int pass = 0, fail = 0;
        AndroidEmulator emulator = new AndroidARM64Emulator("wf3",
                new File("target/rootfs"),
                Collections.<BackendFactory>singletonList(new Unicorn2Factory(true))) {};
        try {
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(23));
            VM vm = emulator.createDalvikVM();
            vm.setJni(new Wf3Spike());
            vm.setVerbose(false);
            vm.setExceptionPropagation(true); // WF3: liga a propagação
            vm.loadLibrary(new File("tests/fase0-spike/libfase0.so"), false);
            DvmClass cls = vm.resolveClass("com/vortexdbg/fase0/Fase0Spike");

            System.out.println("================ WF3 — propagação de exceção ================");

            // T1: doThrow agora lança VortexJniException no host
            boolean threw = false;
            String msg = "";
            try {
                cls.callStaticJniMethod(emulator, "doThrow()V");
            } catch (VortexJniException e) {
                threw = true;
                msg = e.getMessage();
            }
            System.out.println("[T1 exceção propagada] threw=" + threw + " msg=\"" + msg + "\"");
            if (threw) pass++; else fail++;

            // T2: exceção pendente foi limpa ao propagar
            boolean cleared = (vm.getPendingException() == null);
            System.out.println("[T2 pending limpo após throw] " + cleared);
            if (cleared) pass++; else fail++;

            // T3: chamada normal continua funcionando com a propagação ligada
            byte[] data = {0x00, 0x11, 0x22, 0x33};
            ByteArray ba = new ByteArray(vm, data);
            cls.callStaticJniMethod(emulator, "mutate([B)V", ba);
            boolean ok = (ba.getValue()[0] == (byte) (0x00 ^ 0x5A))
                    && vm.getPendingException() == null;
            System.out.println("[T3 chamada normal ok c/ propagação ligada] " + ok);
            if (ok) pass++; else fail++;

            System.out.println("=============================================================");
            System.out.println("RESULTADO WF3: pass=" + pass + " fail=" + fail);
        } finally {
            try { emulator.close(); } catch (Exception ignored) {}
        }
    }
}
