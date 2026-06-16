package com.vortexdbg.ios.dsc

import com.vortexdbg.arm.backend.Unicorn2Factory
import com.vortexdbg.ios.DarwinEmulatorBuilder
import java.io.File
import kotlin.system.exitProcess
import unicorn.Arm64Const

/**
 * Path B [5/2b] — prova o mapeamento LAZY do cache executando código emulado que TOCA o cache
 * sem nada pré-mapeado. Um stub arm64 num scratch page faz dois LDR:
 *   x1 = *(0x180108000)   -> header Mach-O da libobjc (magic 0xFEEDFACF) — região TEXT
 *   x3 = *(0x1eb2b8060)   -> ponteiro do __DATA da libobjc — região com slide V5
 * O DscLazyMapper falta cada página sob demanda (e rebaseia a do DATA). Esperado: x1 baixo =
 * 0xFEEDFACF e x3 = 0x1801596cc (chained ptr -> VA real, rebaseado na própria falta).
 */
object LazyRunDemo {

    // movz x0,#0x8000; movk x0,#0x8010,lsl16; movk x0,#1,lsl32; ldr x1,[x0]
    // movz x2,#0x8060; movk x2,#0xeb2b,lsl16; movk x2,#1,lsl32; ldr x3,[x2]
    private val STUB = byteArrayOf(
        0x00, 0x00, 0x90.toByte(), 0xd2.toByte(), 0x00, 0x02, 0xb0.toByte(), 0xf2.toByte(),
        0x20, 0x00, 0xc0.toByte(), 0xf2.toByte(), 0x01, 0x00, 0x40, 0xf9.toByte(),
        0x02, 0x0c, 0x90.toByte(), 0xd2.toByte(), 0x62, 0x65, 0xbd.toByte(), 0xf2.toByte(),
        0x22, 0x00, 0xc0.toByte(), 0xf2.toByte(), 0x43, 0x00, 0x40, 0xf9.toByte(),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val cfg = VortexIosConfig.load()
        val mainCache = findMainCache(cfg.rootfs.parentFile, cfg.arch)
            ?: run { System.err.println("cache não encontrado (rode fetch-dsc)"); exitProcess(2) }

        println("=== Path B — mapeamento LAZY do cache (executando código emulado) ===")
        val emulator = DarwinEmulatorBuilder.for64Bit().addBackendFactory(Unicorn2Factory(true)).build()
        val lazy = DscLazyMapper(emulator, mainCache)
        try {
            val base = 0x40000000L
            emulator.backend.mem_map(base, 0x4000, 7) // scratch RWX
            emulator.backend.mem_write(base, STUB)

            emulator.backend.emu_start(base, base + STUB.size, 0, STUB.size.toLong() / 4)

            val x1 = emulator.backend.reg_read(Arm64Const.UC_ARM64_REG_X1).toLong()
            val x3 = emulator.backend.reg_read(Arm64Const.UC_ARM64_REG_X3).toLong()
            println("x1 (*0x180108000) = 0x%016x  (magic baixo=0x%08x)".format(x1, x1 and 0xFFFFFFFFL))
            println("x3 (*0x1eb2b8060) = 0x%016x".format(x3))
            println("páginas mapeadas sob demanda=%d, rebaseadas=%d".format(lazy.pagesMapped, lazy.pagesRebased))

            val ok = (x1 and 0xFFFFFFFFL) == 0xFEEDFACFL && x3 == 0x1801596ccL && lazy.pagesMapped >= 2
            println("RESULTADO: " + if (ok) "OK (cache faltado sob demanda; TEXT lido e DATA rebaseado em runtime)" else "FALHOU")
            if (!ok) exitProcess(1)
        } finally {
            lazy.close()
            emulator.close()
        }
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
