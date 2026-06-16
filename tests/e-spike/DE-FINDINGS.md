# D/E — Ponte JNI Bidirecional (Vortex-DBG / A1)

**Branch:** `007/jni-bidirectional`

## E — Java(host) → native(emulado): IMPLEMENTADO ✅
A "outra metade" da fusão. Um método {@code native} de uma classe do app rodando na
host-JVM é roteado para o UniDBG (a `.so` emulada).

**Como:**
- `VortexNativeInstrumentor` (ASM): reescreve cada método `native` — remove o modificador
  e gera um corpo que empacota os args num `Object[]` e chama o `VortexNativeDispatch`.
- `VortexNativeDispatch`: com a `VortexSession` ativa (thread-local), resolve a `DvmClass`
  e executa a função nativa correspondente no UniDBG (`callStaticJniMethod*`), por tipo de
  retorno (void/boolean/int/long/objeto/array).
- `VortexInstrumentingClassLoader`: carrega as classes-alvo child-first e as instrumenta.
- `libe.so` (NDK, ARM64): `nativeSum(int,int)`, `nativeXor(byte[],int)`.

**Prova (`EDemo`, JDK 8):**
```
[T1] EHost.nativeSum(20,22) = 42                 OK   (host -> native emulado)
[T2] EHost.nativeXor([1,2,3],0x5A) = [91,88,89]  OK   (arg+retorno de array)
RESULTADO E: pass=2 fail=0
```

**Limites atuais:** retorno float/double não suportado (raro em JNI de cripto); métodos
de instância nativos seguem o mesmo molde (o demo cobre estáticos). Auto-descoberta da
`.so` por símbolo já funciona via name-export; libs com `JNI_OnLoad`/RegisterNatives usam
`VortexSession.Builder.callJniOnLoad(true)`.

## D — produtização da ponte: estado
Dos 5 subsistemas (doc 05), validados nas fases anteriores:
- **Tabela de handles `int↔objeto`** — funciona (ref tables do `BaseVM`; identidade
  provada na FASE 0 T2). Produtização adicional (substituir `hashCode` por uma
  IndirectReferenceTable real) é refinamento, não correção — não bloqueia.
- **Copy-in/out de array com write-back** — funciona (FASE 0 T1; E T2 reforça com retorno
  de array do native para o host).
- **Identidade / `IsSameObject`** — funciona (FASE 0 T2).
- **Propagação de exceção** — implementada (WF3).
- **`JNIEnv` thread-local** — UniDBG já suporta threads; o `VortexNativeDispatch` é
  thread-local por sessão. Exercício multi-thread real fica para quando um alvo concreto
  spawnar pthreads no nativo.

**Conclusão D/E:** a fusão agora é **bidirecional e funcional**. Refinamentos de D
(IndirectReferenceTable, multi-thread) são incrementais e guiados por alvo real.
