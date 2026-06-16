# G — Debugger Dual-Layer (Vortex-DBG / A1)

**Branch:** `009/debugger`

## O que foi entregue
`com.vortexdbg.app.VortexDebugger` — unifica numa visão só as DUAS camadas:
- **Nativo:** reusa o debugger ARM do UniDBG (`emulator.attach()`): breakpoint por
  símbolo/endereço, PC, registradores (X0..Xn), backtrace (`Unwinder`).
- **Java / fronteira JNI:** envolve o `Jni` (dynamic proxy) e dispara um evento a cada
  cruzamento native→java (qualquer `call*Method`), permitindo "seguir" a execução através
  da ponte e montar a **pilha unificada** (frames nativos + método Java).

Modo não-interativo (callbacks) — base para um frontend interativo (REPL/IDE) depois.

## Demo (`DbgDemo`, JDK 8)
`libdbg.so`: `compute(x)` faz um callback `DbgHost.onStep(x*10)` e retorna `x*2`.

```
[NATIVE BREAK] compute @ 0x120015e0   X0(env)=0xfffe1640   X2(x)=21
    backtrace nativo: []
[JAVA CROSS]  native -> java   com/vortexdbg/dbg/DbgHost->onStep(I)V
    pilha unificada: nativo [RX@0x12001650[libdbg.so]0x1650]  ->  [java onStep(I)V]
compute(21) = 42
RESULTADO G: OK
```

- **Breakpoint nativo** em `compute`: PC + registradores reais (x=21 em X2).
- **Cruzamento JNI** native→java em `onStep`, com a pilha unificada (frame nativo da `.so`
  → método Java).

## API
```java
VortexDebugger dbg = new VortexDebugger(emulator);
dbg.breakNative(module, "Java_..._compute", ctx -> {
    ctx.pc(); ctx.arg(2); ctx.regs(); ctx.nativeStack(6);
});
vm.setJni(dbg.instrument(baseJni, (sig, args) -> { /* cruzamento native->java */ }));
```

## Limites atuais (próximos incrementos)
- O backtrace **no exato entry** da função vem vazio (`[]`) — o frame ainda não foi
  montado (pré-prólogo); no ponto do cruzamento JNI o backtrace já aparece. Resolver com
  breakpoint pós-prólogo ou leitura de LR.
- Java→native (caminho E) também é observável no `VortexNativeDispatch` — integrar como
  mais um tipo de evento na mesma timeline unificada.
- Frontend **interativo** (step/continue/inspect via REPL ou protocolo de IDE) é o próximo
  bloco de UX — o motor (breakpoints + eventos + pilha) já está aqui.
- Resolver símbolo/linha por frame nativo (hoje mostra endereço `module+offset`).

## Nota de implementação
O `Jni` tem variantes `call*Method` (VarArg) e `call*MethodV` (VaList) — a dispatch de
chamada-com-args do native usa a **VarArg**. (Bug do mock de demo, já corrigido; o
`VortexDebugger` intercepta ambas pelo prefixo `call`.)
