package com.vortexdbg.wf4;

import com.vortexdbg.app.VortexClassLoader;
import com.vortexdbg.app.VortexInvoker;

import java.io.File;

/**
 * WF4 — Camada de framework (Vortex-DBG / A1).
 *
 * No WF2 o método StringHolder.get(int) falhou com NoClassDefFoundError:
 * android/util/Base64 — a deobfuscação de strings do app depende do framework Android.
 *
 * Aqui pomos o android-all.jar (Robolectric, AOSP real — não o stub) no classpath do
 * app, de modo que as classes do app que tocam android.* resolvam e executem na JVM host.
 * Prova: StringHolder.get(int) agora deobfusca as strings reais do app.
 */
public class Wf4Spike {

    public static void main(String[] args) throws Exception {
        int pass = 0, fail = 0;

        File appJar = new File("tests/wf2-spike/obfuscated-app.jar");
        File androidAll = new File(androidAllPath());
        if (!appJar.exists() || !androidAll.exists()) {
            System.err.println("faltando jar: app=" + appJar.exists() + " android-all=" + androidAll.exists()
                    + " (" + androidAll + ")");
            System.exit(2);
        }

        // app + framework no mesmo classloader: android.* vem do android-all.
        VortexClassLoader cl = new VortexClassLoader(appJar, androidAll);
        VortexInvoker inv = new VortexInvoker(cl);

        System.out.println("============ WF4 — framework (android-all) ============");
        System.out.println("android.util.Base64 -> " + inv.load("android.util.Base64").getClassLoader());

        // ---- T3 revisitado: StringHolder.get(int) com framework ----
        try {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = 0; i < 8; i++) {
                try {
                    Object s = inv.invokeStatic("org.cf.obfuscated.StringHolder", "get",
                            new Class[]{int.class}, i);
                    sb.append("\n   get(").append(i).append(") = ").append(s);
                    n++;
                } catch (Throwable perItem) {
                    sb.append("\n   get(").append(i).append(") -> ").append(perItem);
                }
            }
            System.out.println("[T3' StringHolder deobfusca com framework]:" + sb);
            if (n > 0) pass++; else fail++;
        } catch (Throwable t) {
            System.out.println("[T3' StringHolder] EXCEÇÃO: " + t);
            fail++;
        }

        System.out.println("======================================================");
        System.out.println("RESULTADO WF4: pass=" + pass + " fail=" + fail);
    }

    private static String androidAllPath() throws Exception {
        java.io.File f = new java.io.File("/tmp/android_all_jar.txt");
        if (f.exists()) {
            return new String(java.nio.file.Files.readAllBytes(f.toPath())).trim();
        }
        return System.getProperty("vortex.androidAll", "");
    }
}
