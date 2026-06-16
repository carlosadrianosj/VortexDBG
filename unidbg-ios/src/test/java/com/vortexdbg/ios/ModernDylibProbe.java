package com.vortexdbg.ios;

import com.vortexdbg.Emulator;
import com.vortexdbg.Module;
import com.vortexdbg.arm.backend.Unicorn2Factory;
import com.vortexdbg.file.ios.DarwinFileIO;
import com.vortexdbg.memory.Memory;

import java.io.File;

/**
 * Probe da Fase 1 (iOS): carrega uma dylib arm64e MODERNA (iOS 18.7.9) da rootfs extraída
 * via fetch-dsc, e mostra ONDE o loader quebra primeiro — esperado: o gap de
 * auth-bind/auth-rebase do FixupChains (FixupChains.java:79/82) com binário real.
 *
 * NÃO usa dylib da Apple do repo: aponta para IOS-files/dsc/rootfs-18.7.9 (não versionado).
 */
public class ModernDylibProbe {

    public static void main(String[] args) throws Exception {
        VortexIosConfig cfg = VortexIosConfig.load();
        File rootfs = cfg.rootfs();
        System.out.println("config: rootfs=" + rootfs + " iOS=" + cfg.version() + " arch=" + cfg.arch());
        String target = args.length > 0 ? args[0] : "usr/lib/libobjc.A.dylib";
        File lib = new File(rootfs, target);
        if (!lib.isFile()) {
            System.err.println("dylib não encontrada: " + lib);
            System.exit(2);
        }

        DarwinEmulatorBuilder builder = DarwinEmulatorBuilder.for64Bit();
        builder.addBackendFactory(new Unicorn2Factory(true)); // Unicorn2 (sem hypervisor/entitlement)
        Emulator<DarwinFileIO> emulator = builder.build();
        try {
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new ExternalDarwinResolver(rootfs));
            emulator.getSyscallHandler().setVerbose(false);

            System.out.println("=== carregando dylib MODERNA: " + target + " (arm64e iOS 18.7.9) ===");
            System.out.println("backend=" + emulator.getBackend());
            try {
                Module m = emulator.loadLibrary(lib);
                System.out.println("CARREGOU: " + m + "  base=0x" + Long.toHexString(m.base));
            } catch (Throwable t) {
                System.out.println(">>> FALHOU em: " + t);
                t.printStackTrace(System.out);
            }
        } finally {
            emulator.close();
        }
    }
}
