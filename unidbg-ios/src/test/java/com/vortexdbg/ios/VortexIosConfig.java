package com.vortexdbg.ios;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Config do Vortex-iOS lida de um JSON externo (não do código). O usuário aponta o JSON
 * para a rootfs que ele extraiu com fetch-dsc — pra trocar de versão de iOS basta editar
 * o JSON, sem recompilar.
 *
 * Localização do JSON (em ordem): -Dvortex.ios.config=... ; env VORTEX_IOS_CONFIG ;
 * caminho canônico em reverselabs/IOS-files/vortex-ios.json.
 *
 * JSON: { "rootfs": "/caminho/dsc/rootfs-18.7.9", "version": "18.7.9", "arch": "arm64e" }
 */
public class VortexIosConfig {

    private static final String DEFAULT_PATH =
            "/Users/carlosadrianosj/Documents/clients/reverselabs/IOS-files/vortex-ios.json";

    private final File rootfs;
    private final String version;
    private final String arch;

    private VortexIosConfig(File rootfs, String version, String arch) {
        this.rootfs = rootfs;
        this.version = version;
        this.arch = arch;
    }

    public static VortexIosConfig load() {
        String path = System.getProperty("vortex.ios.config");
        if (path == null) {
            path = System.getenv("VORTEX_IOS_CONFIG");
        }
        if (path == null) {
            path = DEFAULT_PATH;
        }
        File f = new File(path);
        if (!f.isFile()) {
            throw new IllegalStateException("config iOS não encontrada: " + f
                    + " (defina -Dvortex.ios.config=<json> ou crie o vortex-ios.json)");
        }
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONObject o = JSON.parseObject(text);
            File rootfs = new File(o.getString("rootfs"));
            if (!rootfs.isDirectory()) {
                throw new IllegalStateException("rootfs do JSON não existe: " + rootfs);
            }
            return new VortexIosConfig(rootfs, o.getString("version"), o.getString("arch"));
        } catch (Exception e) {
            throw new IllegalStateException("erro lendo config iOS " + f, e);
        }
    }

    public File rootfs() {
        return rootfs;
    }

    public String version() {
        return version;
    }

    public String arch() {
        return arch;
    }
}
