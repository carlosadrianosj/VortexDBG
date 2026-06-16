package com.vortexdbg.ios;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.backend.Unicorn2Factory;
import com.vortexdbg.file.ios.DarwinFileIO;
import com.vortexdbg.ios.classdump.ClassDumper;
import com.vortexdbg.ios.classdump.IClassDumper;
import com.vortexdbg.ios.ipa.EmulatorConfigurator;
import com.vortexdbg.ios.ipa.IpaLoader;
import com.vortexdbg.ios.ipa.IpaLoader64;
import com.vortexdbg.ios.ipa.LoadedIpa;

import java.io.File;

/**
 * Fase 0 (iOS) — baseline RODÁVEL na árvore iOS 7.1.
 *
 * Carrega o TelegramMessenger-5.11.ipa REAL com o backend Unicorn2 (sem hypervisor/
 * entitlement), roda callEntry() e faz class-dump de classes ObjC VIVAS — o libobjc.A.dylib
 * REAL fazendo o dispatch dentro do emulador. Prova que o motor iOS (Mach-O loader + dyld +
 * runtime ObjC real) funciona neste Mac. É o gate de regressão da Fase 0.
 */
public class IosBaselineTest implements EmulatorConfigurator {

    @Override
    public void configure(Emulator<DarwinFileIO> emulator, String executableBundlePath, File rootDir, String bundleIdentifier) {
    }

    @Override
    public void onExecutableLoaded(Emulator<DarwinFileIO> emulator, MachOModule executable) {
    }

    public static void main(String[] args) throws Exception {
        File ipa = new File("unidbg-ios/src/test/resources/app/TelegramMessenger-5.11.ipa");
        if (!ipa.canRead()) {
            ipa = new File("src/test/resources/app/TelegramMessenger-5.11.ipa");
        }

        long t0 = System.currentTimeMillis();
        IpaLoader ipaLoader = new IpaLoader64(ipa, new File("target/rootfs/ipa-baseline"));
        ipaLoader.addBackendFactory(new Unicorn2Factory(true)); // Unicorn2 — sem entitlement
        LoadedIpa loaded = ipaLoader.load(new IosBaselineTest());
        Emulator<?> emulator = loaded.getEmulator();
        System.out.println("=== iOS Fase 0 baseline — backend=" + emulator.getBackend()
                + " (load " + (System.currentTimeMillis() - t0) + "ms) ===");

        loaded.callEntry();
        System.out.println("callEntry() OK");

        IClassDumper dumper = ClassDumper.getInstance(emulator);
        int ok = 0;
        for (String cls : new String[]{"NSDate", "NSObject", "AppDelegate"}) {
            try {
                String def = dumper.dumpClass(cls);
                int n = def == null ? 0 : def.length();
                System.out.println("dumpClass(" + cls + ") -> " + (n > 0 ? (n + " chars OK") : "vazio"));
                if (n > 0) {
                    ok++;
                    if ("NSDate".equals(cls)) {
                        System.out.println("  amostra:\n" + (def.length() > 240 ? def.substring(0, 240) : def));
                    }
                }
            } catch (Throwable t) {
                System.out.println("dumpClass(" + cls + ") EXCEÇÃO: " + t);
            }
        }
        boolean pass = ok > 0;
        System.out.println("RESULTADO Fase 0 iOS: " + (pass
                ? "OK (libobjc real + dyld + Mach-O loader rodando na árvore 7.1; " + ok + " classes dumpadas)"
                : "FALHOU"));
        emulator.close();
        // Gate de regressão: falha com exit code se o caminho 7.1 quebrar (CI-able).
        if (!pass) {
            System.exit(1);
        }
    }
}
