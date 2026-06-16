package com.vortexdbg.ios;

import com.vortexdbg.Emulator;
import com.vortexdbg.spi.LibraryFile;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.net.MalformedURLException;

/**
 * Resolver que carrega as dylibs do sistema de uma ROOTFS EXTERNA (filesystem), em vez do
 * classpath /ios/7.1 do unidbg-ios. Usado para apontar o Vortex-iOS para um
 * dyld_shared_cache MODERNO extraído via fetch-dsc (iOS 16/17/18, arm64e) — sem por
 * binários da Apple no repo (bring-your-own-dylibs).
 *
 * Probe da Fase 1: ao carregar uma dylib arm64e moderna, exercita o caminho de fixups
 * (FixupChains) com binário real.
 */
public class ExternalDarwinResolver extends DarwinResolver {

    private final File rootDir;

    public ExternalDarwinResolver(File rootDir) {
        super();
        this.rootDir = rootDir;
    }

    @Override
    public LibraryFile resolveLibrary(Emulator<?> emulator, String libraryName) {
        if (libraryName == null || libraryName.contains("@")) {
            return null;
        }
        String norm = FilenameUtils.normalize(libraryName, true);
        if (norm == null) {
            return null;
        }
        String rel = norm.startsWith("/") ? norm.substring(1) : norm;
        File f = new File(rootDir, rel);
        if (f.isFile()) {
            try {
                return new URLibraryFile(f.toURI().toURL(), libraryName, this);
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
        return null; // não encontrado na rootfs moderna -> null (deixa explícito o que falta)
    }
}
