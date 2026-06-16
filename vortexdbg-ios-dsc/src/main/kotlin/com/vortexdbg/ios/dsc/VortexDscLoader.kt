package com.vortexdbg.ios.dsc

import com.vortexdbg.Emulator
import java.io.File
import unicorn.Arm64Const

/**
 * Path B — API pública do subsistema dyld_shared_cache do Vortex. Junta os tijolos num único
 * ponto de uso: com o cache faltado sob demanda por baixo (DscLazyMapper para DATA), resolve
 * símbolos exportados (DscFileResolver) e CHAMA funções do cache no emulador, pré-mapeando a
 * região de código sob demanda (FETCH não pode ser lazy — corromperia o tradutor do QEMU).
 *
 * Uso típico:
 *   val loader = VortexDscLoader.from(VortexIosConfig.load(), emulator)
 *   val strlen = loader.resolve("/usr/lib/system/libsystem_platform.dylib", "__platform_strlen")!!
 *   val n = loader.call(strlen, loader.cString("hello"))
 */
class VortexDscLoader(private val emulator: Emulator<*>, private val mainCache: File) {

    val lazy = DscLazyMapper(emulator, mainCache)
    private val resolver = DscFileResolver(mainCache)
    private val mapper = DscMapper(emulator)
    private val files = DyldSharedCache.cacheFiles(mainCache)

    // Scratch para argumentos/pilha/sentinela de retorno.
    private val scratchBase = 0x3F000000L
    private val sentinel = scratchBase + 0x1000
    private val stackTop = scratchBase + 0x80000
    private var bump = scratchBase + 0x80000  // dados crescem acima da pilha

    private val mappedCode = ArrayList<LongRange>()

    init {
        emulator.backend.mem_map(scratchBase, 0x200000, 7) // 2 MB RWX: sentinela + pilha + dados
    }

    val imagePaths: Set<String> get() = resolver.imagePaths

    /** VA de um símbolo exportado direto por [dylibPath]. */
    fun resolve(dylibPath: String, symbol: String): ULong? = resolver.resolve(dylibPath, symbol)

    /** Escreve uma C-string no scratch e devolve o ponteiro (para passar como argumento). */
    fun cString(s: String): Long {
        val bytes = (s.toByteArray(Charsets.UTF_8) + 0)
        val ptr = bump
        emulator.backend.mem_write(ptr, bytes)
        bump += (bytes.size.toLong() + 15) and 0xFL.inv()
        return ptr
    }

    /** Garante que a região de código que contém [vmaddr] está mapeada (uma vez por região). */
    private fun ensureCodeMapped(vmaddr: ULong): Boolean {
        val v = vmaddr.toLong()
        if (mappedCode.any { v in it }) return true
        val mp = mapper.mapRegionContaining(files, vmaddr) ?: return false
        mappedCode.add(mp.address.toLong() until (mp.address + mp.size).toLong())
        return true
    }

    /**
     * Chama a função do cache em [funcAddr] com [args] (até 8, em x0..x7) e devolve x0. O código
     * é pré-mapeado; os DADOS tocados são faltados sob demanda. Para quando a função faz RET
     * (PC == sentinela).
     */
    fun call(funcAddr: ULong, vararg args: Long): Long {
        require(args.size <= 8) { "máx. 8 args (x0..x7)" }
        ensureCodeMapped(funcAddr)
        val backend = emulator.backend
        for (i in args.indices) backend.reg_write(Arm64Const.UC_ARM64_REG_X0 + i, args[i])
        backend.reg_write(Arm64Const.UC_ARM64_REG_LR, sentinel)
        backend.reg_write(Arm64Const.UC_ARM64_REG_SP, stackTop)
        backend.emu_start(funcAddr.toLong(), sentinel, 0, 0)
        return backend.reg_read(Arm64Const.UC_ARM64_REG_X0).toLong()
    }

    fun close() = lazy.close()

    companion object {
        fun from(config: VortexIosConfig, emulator: Emulator<*>): VortexDscLoader {
            val main = findMainCache(config.rootfs.parentFile, config.arch)
                ?: throw IllegalStateException("dyld_shared_cache_${config.arch} não encontrado em ${config.rootfs.parentFile} (rode fetch-dsc)")
            return VortexDscLoader(emulator, main)
        }

        private fun findMainCache(dir: File, arch: String): File? {
            dir.listFiles { f -> f.isDirectory }?.forEach { d ->
                val c = File(d, "dyld_shared_cache_$arch")
                if (c.isFile) return c
            }
            val c = File(dir, "dyld_shared_cache_$arch")
            return if (c.isFile) c else null
        }
    }
}
