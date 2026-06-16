package com.vortexdbg.ios.dsc;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Path B (Vortex-iOS) — leitor do dyld_shared_cache. PRIMEIRO TIJOLO do subsistema que
 * mapeia o cache INTEIRO na memória do emulador (em vez de extrair dylibs individuais, que
 * o R1 provou inviável).
 *
 * Parseia o header (magic) e as MAPPINGS de um arquivo de cache (o principal ou um
 * sub-cache `.NN`): cada mapping diz {address, size, fileOffset, prot} — exatamente o que
 * será mmap'd na memória emulada (no vmaddr do cache). O cache moderno é fatiado em vários
 * arquivos; cada um é um cache completo com header+mappings próprios, então basta iterar os
 * arquivos e parsear cada um.
 *
 * Layout (estável): dyld_cache_header.magic[16]@0, mappingOffset(u32)@16, mappingCount(u32)@20;
 * dyld_cache_mapping_info (32B): address(u64)@0, size@8, fileOffset@16, maxProt(u32)@24, initProt@28.
 */
public class DyldSharedCache {

    public static final class Mapping {
        public final long address;
        public final long size;
        public final long fileOffset;
        public final int maxProt;
        public final int initProt;

        Mapping(long address, long size, long fileOffset, int maxProt, int initProt) {
            this.address = address;
            this.size = size;
            this.fileOffset = fileOffset;
            this.maxProt = maxProt;
            this.initProt = initProt;
        }

        @Override
        public String toString() {
            return String.format("addr=0x%x size=0x%x (%dKB) fileOff=0x%x prot=%d/%d",
                    address, size, size / 1024, fileOffset, initProt, maxProt);
        }
    }

    private final File file;
    private final String magic;
    private final List<Mapping> mappings = new ArrayList<>();

    public DyldSharedCache(File file) throws IOException {
        this.file = file;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] head = new byte[0x10000];
            int n = raf.read(head);
            if (n < 0x100) {
                throw new IOException("cache muito pequeno: " + file);
            }
            ByteBuffer bb = ByteBuffer.wrap(head, 0, n).order(ByteOrder.LITTLE_ENDIAN);

            byte[] m = new byte[16];
            bb.position(0);
            bb.get(m);
            int z = 0;
            while (z < 16 && m[z] != 0) {
                z++;
            }
            this.magic = new String(m, 0, z, StandardCharsets.US_ASCII);
            if (!magic.startsWith("dyld_v1")) {
                throw new IOException("não é dyld_shared_cache (magic='" + magic + "'): " + file);
            }

            int mappingOffset = bb.getInt(16);
            int mappingCount = bb.getInt(20);
            for (int i = 0; i < mappingCount; i++) {
                int off = mappingOffset + i * 32;
                long address = bb.getLong(off);
                long size = bb.getLong(off + 8);
                long fileOffset = bb.getLong(off + 16);
                int maxProt = bb.getInt(off + 24);
                int initProt = bb.getInt(off + 28);
                mappings.add(new Mapping(address, size, fileOffset, maxProt, initProt));
            }
        }
    }

    public File file() {
        return file;
    }

    public String magic() {
        return magic;
    }

    public List<Mapping> mappings() {
        return Collections.unmodifiableList(mappings);
    }

    /** Os arquivos do cache: o principal + os sub-caches `.NN` (na mesma pasta). */
    public static List<File> cacheFiles(File mainCache) {
        List<File> files = new ArrayList<>();
        files.add(mainCache);
        File dir = mainCache.getParentFile();
        String base = mainCache.getName();
        File[] all = dir.listFiles((d, name) -> name.startsWith(base + "."));
        if (all != null) {
            List<File> subs = new ArrayList<>();
            for (File f : all) {
                String ext = f.getName().substring(base.length() + 1);
                if (ext.matches("\\d+")) { // só os sub-caches .NN (ignora .map etc.)
                    subs.add(f);
                }
            }
            subs.sort((a, b) -> a.getName().compareTo(b.getName()));
            files.addAll(subs);
        }
        return files;
    }
}
