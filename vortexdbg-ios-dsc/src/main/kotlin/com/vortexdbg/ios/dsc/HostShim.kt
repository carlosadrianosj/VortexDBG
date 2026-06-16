package com.vortexdbg.ios.dsc

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.CodeHook
import unicorn.Arm64Const

/**
 * Path B [shim] — intercepta funções do cache (malloc & cia) e as substitui por implementação
 * Java, evitando o bootstrap do dyld4 que inicializaria o libsystem de baixo nível. Instala um
 * CodeHook no ENDEREÇO da função: ao entrar, lê os args (x0..), computa o resultado em Java,
 * escreve x0 e força o retorno (PC = LR) — o corpo real nunca executa.
 *
 * É a base do caminho pro runtime ObjC ao vivo: com malloc/locks shimados, dá pra rodar
 * _objc_init e objc_getClass sem precisar do boot real do userland.
 */
class HostShim(
    private val emulator: Emulator<*>,
    private val resolver: DscFileResolver,
    private val heap: HostHeap,
) {

    var calls = 0; private set
    private val installed = ArrayList<String>()

    private fun arg(backend: Backend, i: Int): Long = backend.reg_read(Arm64Const.UC_ARM64_REG_X0 + i).toLong()

    /** Instala um shim na função [symbol] de [dylib]. [handler] recebe (backend,args)->valor de x0. */
    private fun shim(dylib: String, symbol: String, handler: (Backend, LongArray) -> Long): Boolean {
        val addr = resolver.resolve(dylib, symbol)?.toLong() ?: return false
        emulator.backend.hook_add_new(object : CodeHook {
            override fun hook(backend: Backend, address: Long, size: Int, user: Any?) {
                val args = LongArray(4) { arg(backend, it) }
                val ret = handler(backend, args)
                backend.reg_write(Arm64Const.UC_ARM64_REG_X0, ret)
                backend.reg_write(Arm64Const.UC_ARM64_REG_PC, backend.reg_read(Arm64Const.UC_ARM64_REG_LR).toLong())
                calls++
            }
            override fun onAttach(unHook: com.vortexdbg.arm.backend.UnHook) {}
            override fun detach() {}
        }, addr, addr, null)
        installed.add(symbol)
        return true
    }

    /** Instala o conjunto malloc (resolve de libsystem_malloc). Retorna os símbolos instalados. */
    fun installMalloc(): List<String> {
        val LIB = "/usr/lib/system/libsystem_malloc.dylib"
        shim(LIB, "_malloc") { _, a -> heap.alloc(a[0]) }
        shim(LIB, "_calloc") { _, a -> heap.allocZeroed(a[0] * a[1]) }
        shim(LIB, "_free") { _, _ -> 0 }
        shim(LIB, "_realloc") { backend, a ->
            val old = a[0]; val n = a[1]
            if (old == 0L) return@shim heap.alloc(n)
            if (n == 0L) { heap.free(old); return@shim 0 }
            val np = heap.alloc(n)
            val copy = minOf(n, heap.sizeOf(old))
            if (copy > 0) backend.mem_write(np, backend.mem_read(old, copy))
            np
        }
        shim(LIB, "_malloc_size") { _, a -> heap.sizeOf(a[0]) }
        return installed.toList()
    }
}
