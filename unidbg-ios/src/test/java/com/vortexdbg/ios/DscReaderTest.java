package com.vortexdbg.ios;

import com.vortexdbg.ios.dsc.DyldSharedCache;

import java.io.File;
import java.util.List;

/**
 * Path B — teste do primeiro tijolo: parsear o dyld_shared_cache (main + sub-caches) e
 * listar as mappings que serão mapeadas na memória do emulador. Valida contra o que o
 * `ipsw dyld info` reporta (magic, addr base 0x180000000, nº de sub-caches).
 *
 * Aponta para a rootfs do JSON (VortexIosConfig); o cache fica em ../<...>/dyld_shared_cache_arm64e.
 */
public class DscReaderTest {

    public static void main(String[] args) throws Exception {
        VortexIosConfig cfg = VortexIosConfig.load();
        // o cache fica no diretório irmão da rootfs split (dsc/<build>__<device>/dyld_shared_cache_<arch>)
        File dscDir = new File(cfg.rootfs().getParentFile(), "");
        File mainCache = findMainCache(dscDir, cfg.arch());
        if (mainCache == null) {
            System.err.println("cache não encontrado sob " + dscDir + " (rode fetch-dsc)");
            System.exit(2);
        }

        System.out.println("=== Path B — DyldSharedCache reader (" + cfg.version() + " " + cfg.arch() + ") ===");
        List<File> files = DyldSharedCache.cacheFiles(mainCache);
        System.out.println("arquivos do cache (main + sub-caches): " + files.size());

        long totalMapped = 0;
        int totalMappings = 0;
        long minAddr = Long.MAX_VALUE, maxAddr = 0;
        String mainMagic = null;
        for (int i = 0; i < files.size(); i++) {
            DyldSharedCache c = new DyldSharedCache(files.get(i));
            if (i == 0) {
                mainMagic = c.magic();
            }
            for (DyldSharedCache.Mapping mp : c.mappings()) {
                totalMappings++;
                totalMapped += mp.size;
                minAddr = Math.min(minAddr, mp.address);
                maxAddr = Math.max(maxAddr, mp.address + mp.size);
            }
            if (i < 3) {
                System.out.println("  " + files.get(i).getName() + "  magic='" + c.magic() + "'  mappings:");
                for (DyldSharedCache.Mapping mp : c.mappings()) {
                    System.out.println("      " + mp);
                }
            }
        }
        System.out.printf("TOTAL: %d arquivos, %d mappings, %.1f GB mapeáveis, região 0x%x -> 0x%x%n",
                files.size(), totalMappings, totalMapped / 1024.0 / 1024.0 / 1024.0, minAddr, maxAddr);

        boolean ok = mainMagic != null && mainMagic.startsWith("dyld_v1")
                && minAddr == 0x180000000L && files.size() > 1;
        System.out.println("RESULTADO Path B (reader): " + (ok
                ? "OK (cache parseado: magic '" + mainMagic + "', base 0x180000000, " + files.size() + " arquivos)"
                : "FALHOU"));
        if (!ok) {
            System.exit(1);
        }
    }

    private static File findMainCache(File dir, String arch) {
        File[] subdirs = dir.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File d : subdirs) {
                File c = new File(d, "dyld_shared_cache_" + arch);
                if (c.isFile()) {
                    return c;
                }
            }
        }
        File c = new File(dir, "dyld_shared_cache_" + arch);
        return c.isFile() ? c : null;
    }
}
