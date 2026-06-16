package com.vortexdbg.ios.dsc

import com.alibaba.fastjson.JSON
import java.io.File

/**
 * Config do Vortex-iOS lida de um JSON externo (não do código). Aponta a rootfs/cache que
 * o usuário extraiu com fetch-dsc — trocar de versão = editar o JSON, sem recompilar.
 *
 * Localização (em ordem): -Dvortex.ios.config ; env VORTEX_IOS_CONFIG ; candidatos relativos
 * ao diretório atual (o vortex-ios.json gerado por scripts/ios/fetch-dsc.sh). SEM caminho
 * absoluto hardcoded — o JSON é gerado pelo script com os caminhos da máquina de quem roda.
 * JSON: { "rootfs": "/.../dsc/rootfs-18.7.9", "version": "18.7.9", "arch": "arm64e" }
 */
data class VortexIosConfig(val rootfs: File, val version: String, val arch: String) {

    companion object {
        // Candidatos relativos ao CWD (a raiz do repo, ao rodar os demos). O fetch-dsc.sh grava
        // em scripts/ios/vortex-ios.json por padrão.
        private val CANDIDATES = listOf("vortex-ios.json", "scripts/ios/vortex-ios.json")

        fun load(): VortexIosConfig {
            val path = System.getProperty("vortex.ios.config")
                ?: System.getenv("VORTEX_IOS_CONFIG")
                ?: CANDIDATES.firstOrNull { File(it).isFile }
                ?: throw IllegalStateException(
                    "config iOS não encontrada. Rode scripts/ios/fetch-dsc.sh (baixa o cache e gera o " +
                        "vortex-ios.json) e/ou defina -Dvortex.ios.config=<json> ou VORTEX_IOS_CONFIG=<json>."
                )
            val f = File(path)
            require(f.isFile) {
                "config iOS não encontrada: $f (rode scripts/ios/fetch-dsc.sh ou defina VORTEX_IOS_CONFIG)"
            }
            val o = JSON.parseObject(f.readText())
            val rootfs = File(o.getString("rootfs"))
            require(rootfs.isDirectory) { "rootfs do JSON não existe: $rootfs" }
            return VortexIosConfig(rootfs, o.getString("version"), o.getString("arch"))
        }
    }
}
