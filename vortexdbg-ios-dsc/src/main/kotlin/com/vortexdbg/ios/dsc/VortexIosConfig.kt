package com.vortexdbg.ios.dsc

import com.alibaba.fastjson.JSON
import java.io.File

/**
 * Config do Vortex-iOS lida de um JSON externo (não do código). Aponta a rootfs/cache que
 * o usuário extraiu com fetch-dsc — trocar de versão = editar o JSON, sem recompilar.
 *
 * Localização (em ordem): -Dvortex.ios.config ; env VORTEX_IOS_CONFIG ; caminho canônico.
 * JSON: { "rootfs": "/.../dsc/rootfs-18.7.9", "version": "18.7.9", "arch": "arm64e" }
 */
data class VortexIosConfig(val rootfs: File, val version: String, val arch: String) {

    companion object {
        private const val DEFAULT_PATH =
            "/Users/carlosadrianosj/Documents/clients/reverselabs/IOS-files/vortex-ios.json"

        fun load(): VortexIosConfig {
            val path = System.getProperty("vortex.ios.config")
                ?: System.getenv("VORTEX_IOS_CONFIG")
                ?: DEFAULT_PATH
            val f = File(path)
            require(f.isFile) {
                "config iOS não encontrada: $f (defina -Dvortex.ios.config=<json> ou crie o vortex-ios.json)"
            }
            val o = JSON.parseObject(f.readText())
            val rootfs = File(o.getString("rootfs"))
            require(rootfs.isDirectory) { "rootfs do JSON não existe: $rootfs" }
            return VortexIosConfig(rootfs, o.getString("version"), o.getString("arch"))
        }
    }
}
