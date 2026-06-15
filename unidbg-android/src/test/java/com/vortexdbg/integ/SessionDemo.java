package com.vortexdbg.integ;

import com.vortexdbg.app.VortexSession;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.DvmObject;

import java.io.File;
import java.nio.file.Files;

/**
 * Demo do {@link VortexSession} (B — API de orquestração).
 * Refaz o capstone (native↔app↔framework) + uma invocação host, agora pela fachada.
 */
public class SessionDemo {

    public static void main(String[] args) throws Exception {
        File androidAll = new File(new String(Files.readAllBytes(new File("/tmp/android_all_jar.txt").toPath())).trim());

        try (VortexSession s = VortexSession.builder()
                .classes(new File("wf2-spike/obfuscated-app.jar"))
                .androidAll(androidAll)
                .nativeLib(new File("integ-spike/libinteg.so"))
                .open()) {

            System.out.println("=== VortexSession aberta — backend=" + s.emulator().getBackend() + " ===");

            // 1) invocação host pura (estilo LSPosed)
            Object enc = s.invokeStatic("org.cf.crypto.XORCrypt", "encode",
                    new Class[]{String.class, String.class}, "hello session", "k3y");
            System.out.println("host invoke  XORCrypt.encode = " + enc);

            // 2) native -> app -> framework (capstone) pela mesma sessão
            DvmClass cls = s.resolveNativeClass("com/vortexdbg/integ/IntegSpike");
            DvmObject<?> ret = cls.callStaticJniMethodObject(s.emulator(),
                    "nativeStringGet(I)Ljava/lang/String;", 0);
            System.out.println("native->app->framework StringHolder.get(0) = " + ret.getValue());

            boolean ok = enc != null && ret.getValue() != null;
            System.out.println("RESULTADO B: " + (ok ? "OK (sessão consolida native+app+framework)" : "FALHOU"));
        }
    }
}
