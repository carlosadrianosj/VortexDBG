package com.vortexdbg.wf4;

import com.vortexdbg.app.VortexClassLoader;
import com.vortexdbg.app.VortexFramework;
import com.vortexdbg.app.VortexInvoker;

import java.io.File;
import java.nio.file.Files;

/**
 * Demo do {@link VortexFramework} (A — camada de framework produtizada).
 * Reexecuta a deobfuscação do WF4, agora pela API limpa em vez do fio solto.
 */
public class VortexFrameworkDemo {

    public static void main(String[] args) throws Exception {
        File appJar = new File("wf2-spike/obfuscated-app.jar");
        File androidAll = new File(new String(Files.readAllBytes(new File("/tmp/android_all_jar.txt").toPath())).trim());

        VortexFramework framework = VortexFramework.fromAndroidAll(androidAll);
        VortexClassLoader cl = framework.newAppClassLoader(appJar);
        VortexInvoker inv = new VortexInvoker(cl);

        System.out.println("=== VortexFramework: " + framework.frameworkJars().size() + " jar(s) de framework ===");
        int ok = 0;
        for (int i = 0; i < 4; i++) {
            Object s = inv.invokeStatic("org.cf.obfuscated.StringHolder", "get", new Class[]{int.class}, i);
            System.out.println("  StringHolder.get(" + i + ") = " + s);
            if (s != null) ok++;
        }
        System.out.println("RESULTADO: " + (ok == 4 ? "OK (framework produtizado funciona)" : "FALHOU"));
    }
}
