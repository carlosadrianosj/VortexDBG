# WF3 — Endurecimento da Ponte JNI (Vortex-DBG / A1)

**Branch:** `003/wf3` (cópia de `002/wf4`)
**Objetivo:** fechar a lacuna que a FASE 0 mediu — propagação de exceção native→host —
e formalizar o acesso à exceção pendente.

## O que foi feito (core do UniDBG, mínimo e opt-in)
- `VM` (interface): novos métodos
  `setExceptionPropagation(boolean)` / `isExceptionPropagation()` /
  `getPendingException()` / `clearPendingException()`.
- `BaseVM`: campo `boolean exceptionPropagation` + implementação (sobre o `throwable`
  existente, setado por `ThrowNew`/`Throw`).
- `DvmObject.callJniMethod`: após `Module.emulateFunction`, se a propagação estiver
  ligada e houver exceção pendente, limpa e lança `VortexJniException` no host.
- `VortexJniException` (novo): carrega o `DvmObject` pendente; a mensagem lê a string C
  da exceção nativa (via `Pointer.getString`).

**Opt-in (default false):** comportamento do UniDBG upstream é preservado — o caminho
quente de `callJniMethod` só muda quando `setExceptionPropagation(true)`.

## Validação (`Wf3Spike`, reusa `fase0-spike/libfase0.so`)
```
[T1 exceção propagada]  threw=true  msg="java.lang.RuntimeException: fase0 native throw"
[T2 pending limpo após throw]  true
[T3 chamada normal ok c/ propagação ligada]  true
RESULTADO WF3: pass=3 fail=0
```
- T1: `doThrow()` nativo → `VortexJniException` no host, com a **mensagem real** extraída
  do ponteiro nativo.
- T2: a exceção pendente é limpa ao propagar (semântica `ExceptionClear`).
- T3: chamadas normais (`mutate`) seguem funcionando com a propagação ligada.

### Rodar (JDK 8 compila e roda; sem android-all aqui)
```bash
export JAVA_HOME=$(cat /tmp/jdk8_home.txt)
./mvnw -o -q -pl unidbg-android install -DskipTests -Dgpg.skip=true
./mvnw -o -q -pl unidbg-android test-compile -Dmaven.test.skip=false -Dgpg.skip=true
CP="unidbg-android/target/test-classes:unidbg-android/target/classes:$(cat /tmp/cp.txt)"
"$JAVA_HOME/bin/java" -cp "$CP" com.vortexdbg.wf3.Wf3Spike
```

## Estado dos 5 subsistemas da ponte JNI (doc 05)
| Subsistema | Estado |
|---|---|
| Tabela de handles `int↔objeto` | ✅ funciona (ref tables do `BaseVM`; identidade provada na FASE 0 T2) |
| Copy-in/out de array com write-back | ✅ funciona (FASE 0 T1; handle = `DvmObject`, lê via `getValue()`) |
| `IsSameObject` / identidade | ✅ funciona (FASE 0 T2) |
| **Propagação de exceção** | ✅ **implementada aqui (WF3)** |
| `JNIEnv` thread-local | ⏳ não exercitado (UniDBG já suporta threads; validar quando necessário) |

## Pendência conectada
- Integração `ProxyClassFactory` (DvmClass → classe real do app via `VortexClassLoader`)
  para o E2E completo **native↔Java(app)↔framework**. Esta é a próxima síntese (junta
  FASE 0 + WF2 + WF4), candidata à próxima branch enumerada.
