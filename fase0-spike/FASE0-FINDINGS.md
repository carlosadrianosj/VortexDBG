# FASE 0 — De-risk da Ponte JNI (Vortex-DBG / Arquitetura A1)

**Branch:** `fase0-jni-bridge-spike` (a partir de `001-begin`, pós-WF1)
**Objetivo:** provar empiricamente que o lado nativo (`.so` emulado pelo UniDBG) e o lado
Java (objeto real no heap da JVM host) conseguem conversar pela ponte JNI preservando
**mutação de array (write-back)**, **identidade de objeto** e **propagação de exceção** —
as 3 propriedades que o relatório de viabilidade apontou como o maior risco de A1.

## Artefatos
- `fase0.c` — 3 funções JNI name-exported (mutate / isSame / doThrow).
- `libfase0.so` — compilado p/ arm64 com NDK 26 (`aarch64-linux-android21-clang`).
- `../unidbg-android/src/test/java/com/vortexdbg/fase0/Fase0Spike.java` — driver (main()).

### Como rodar
```bash
export JAVA_HOME=$(cat /tmp/jdk8_home.txt)   # Zulu 8
# (re)compilar o .so:  CLANG=$(cat /tmp/ndk_clang.txt); "$CLANG" -shared -fPIC -O2 -o fase0-spike/libfase0.so fase0-spike/fase0.c
./mvnw -o -q -pl unidbg-android test-compile -Dmaven.test.skip=false -Dgpg.skip=true
CP="unidbg-android/target/test-classes:unidbg-android/target/classes:$(cat /tmp/cp.txt)"
"$JAVA_HOME/bin/java" -cp "$CP" com.vortexdbg.fase0.Fase0Spike
```

## Resultados

| # | Propriedade | Resultado | Evidência |
|---|---|---|---|
| **T1** | **Write-back de `byte[]`** | ✅ **OK** | native fez `GetByteArrayElements`+`Release(mode 0)` XOR 0x5A: `00112233`→`5a4b7869`; o lado Java lê a mutação via `ByteArray.getValue()`. |
| **T2** | **Identidade (`IsSameObject`)** | ✅ **OK** | `isSame(s1,s1)=true`, `isSame(s1,s2)=false` através da ponte. |
| **T3** | **Propagação de exceção** | ⚠️ **parcial** | `ThrowNew` nativo **não** vira exceção no host automaticamente. |

**Veredito: A1 VIÁVEL.** As duas propriedades mais difíceis (espelhamento de array com
write-back e identidade de objeto) **já funcionam** sobre a infraestrutura existente do
UniDBG. A lacuna (exceção) é pequena, conhecida e escopada ao WF3.

## Descobertas detalhadas (acionáveis para o WF3)

1. **Write-back NÃO muta a instância `byte[]` original.** `ByteArray._ReleaseArrayCritical`
   (mode 0/COMMIT) faz `setValue(elems.getByteArray(...))` — **substitui** o array interno
   por um novo lido da memória Unicorn. Logo:
   - O **handle estável é o `DvmObject`/`ByteArray`**, não a `byte[]` crua passada.
   - A mutação se lê via `getValue()` (não segurando a referência original).
   - **Implicação A1:** ao plugar objetos reais do app, a camada deve tratar o `DvmObject`
     como a identidade canônica e refazer o binding `byte[]` no write-back (ou expor a
     mutação de volta ao objeto host explicitamente).

2. **Identidade funciona** via a tabela de referências do `BaseVM` (handle = `hashCode`).
   `IsSameObject` (`DalvikVM64`) compara corretamente o mesmo handle. Para A1, ao mapear
   objetos reais do app, basta garantir um `DvmObject` estável por instância host.

3. **Exceção nativa fica PENDENTE, não propaga.** `ThrowNew` (`DalvikVM64:184-193`) faz
   `BaseVM.throwable = dvmObject`; `ExceptionCheck` (`:3579`) lê esse campo. Mas:
   - `callStaticJniMethod` **não** checa `throwable` ao retornar nem lança no host.
   - Não há **getter público** para `throwable` (só `VM.throwException(DvmObject)` setter).
   - **Trabalho WF3:** (a) expor a exceção pendente (ex.: `VM.getPendingException()`),
     (b) após cada chamada nativa, traduzir o `DvmObject` pendente em exceção do host e
     lançá-la (ou limpar via `ExceptionClear`), replicando a semântica JNI
     `ExceptionOccurred`/`ExceptionCheck`/`Throw`.

## Conexão com o plano
- Confirma o doc `05-jni-bridge.md`: write-back/identidade reusam a infra do UniDBG;
  exceção é subsistema a construir. Os 5 subsistemas do WF3 seguem válidos, com a
  exceção agora **medida** (mecanismo pendente existe, falta wiring + acessor).
- Próximos: **WF2** (carregar classes reais do app via JEB/dex2jar na JVM host e
  substituir o mock `DvmObject` por objeto real) e **WF3** (endurecer a ponte, incl. o
  wiring de exceção acima).
