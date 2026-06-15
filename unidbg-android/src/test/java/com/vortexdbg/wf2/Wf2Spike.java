package com.vortexdbg.wf2;

import com.vortexdbg.app.VortexClassLoader;
import com.vortexdbg.app.VortexInvoker;

import java.io.File;
import java.util.Arrays;

/**
 * WF2 — Java engine & classloading (arquitetura A1).
 *
 * Prova que o Vortex-DBG executa CLASSES REAIS do app na JVM host (off-device),
 * substituindo o fluxo LSPosed/Zygote. Carrega obfuscated-app.jar (dex2jar do
 * obfuscated-app.apk) e invoca métodos com argumentos, recebendo o retorno real.
 *
 * Alvos (Java puro do app org.cf.*):
 *   T1) XORCrypt.encode/decode(String,String)  — round-trip
 *   T2) MathCrypt.encode(int)->int[] / decode(int[])->int — round-trip c/ array
 *   T3) StringHolder.get(int) — máquina de deobfuscação de strings do app
 */
public class Wf2Spike {

    public static void main(String[] args) throws Exception {
        int pass = 0, fail = 0;
        File jar = new File("wf2-spike/obfuscated-app.jar");
        if (!jar.exists()) {
            System.err.println("jar não encontrado: " + jar.getAbsolutePath());
            System.exit(2);
        }

        VortexClassLoader cl = new VortexClassLoader(jar);
        VortexInvoker inv = new VortexInvoker(cl);

        System.out.println("================ WF2 — classes do app na JVM host ================");
        System.out.println("classloader do app: " + inv.load("org.cf.crypto.XORCrypt").getClassLoader());

        // ---- T1: XORCrypt round-trip ----
        try {
            String msg = "hello vortex-dbg";
            String key = "k3y!";
            String enc = (String) inv.invokeStatic("org.cf.crypto.XORCrypt", "encode",
                    new Class[]{String.class, String.class}, msg, key);
            String dec = (String) inv.invokeStatic("org.cf.crypto.XORCrypt", "decode",
                    new Class[]{String.class, String.class}, enc, key);
            boolean ok = msg.equals(dec);
            System.out.println("[T1 XORCrypt] msg=\"" + msg + "\" -> encode=\"" + enc + "\" -> decode=\"" + dec + "\"  " + (ok ? "OK" : "FALHOU"));
            if (ok) pass++; else fail++;
        } catch (Throwable t) {
            System.out.println("[T1 XORCrypt] EXCEÇÃO: " + t);
            fail++;
        }

        // ---- T2: MathCrypt encode/decode (array) ----
        try {
            int secret = 1337;
            int[] enc = (int[]) inv.invokeStatic("org.cf.obfuscated.MathCrypt", "encode",
                    new Class[]{int.class}, secret);
            int dec = (Integer) inv.invokeStatic("org.cf.obfuscated.MathCrypt", "decode",
                    new Class[]{int[].class}, (Object) enc);
            boolean ok = (dec == secret);
            System.out.println("[T2 MathCrypt] " + secret + " -> encode=" + Arrays.toString(enc) + " -> decode=" + dec + "  " + (ok ? "OK" : "FALHOU"));
            if (ok) pass++; else fail++;
        } catch (Throwable t) {
            System.out.println("[T2 MathCrypt] EXCEÇÃO: " + t);
            fail++;
        }

        // ---- T3: StringHolder.get (deobfuscação) ----
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                Object s = inv.invokeStatic("org.cf.obfuscated.StringHolder", "get",
                        new Class[]{int.class}, i);
                sb.append("\n   get(").append(i).append(") = ").append(s);
            }
            System.out.println("[T3 StringHolder] strings deobfuscadas:" + sb);
            pass++;
        } catch (Throwable t) {
            System.out.println("[T3 StringHolder] EXCEÇÃO (provável dependência de android.* -> WF4): " + t);
            fail++;
        }

        System.out.println("==================================================================");
        System.out.println("RESULTADO WF2: pass=" + pass + " fail=" + fail);
    }
}
