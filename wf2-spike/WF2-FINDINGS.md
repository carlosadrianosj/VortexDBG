# WF2 — Java Engine & Classloading (Vortex-DBG / A1)

**Branch:** `001-begin`
**Objetivo:** executar CLASSES REAIS do app na JVM host (off-device), invocando métodos
com argumentos e recebendo o retorno real — o substituto hermético do LSPosed/Zygote.

## Pipeline
`obfuscated-app.apk` --dex2jar v2.4--> `wf2-spike/obfuscated-app.jar` (.class)
--> `VortexClassLoader` (URLClassLoader) --> `VortexInvoker` (reflexão) --> JVM host.

> Front-end usado: **dex2jar** (automação). Para apps protegidos, o front-end de produção
> é o **JEB** (mesmo contrato: produz `.class`/dex limpo). Troca-se só o passo de extração.

## Código de produção
- `com.vortexdbg.app.VortexClassLoader` — carrega `.class`/`.jar` do app num classloader dedicado.
- `com.vortexdbg.app.VortexInvoker` — invoca método (estático/instância) por reflexão com args.
- Spike: `unidbg-android/src/test/java/com/vortexdbg/wf2/Wf2Spike.java`.

### Rodar
```bash
export JAVA_HOME=$(cat /tmp/jdk8_home.txt)
./mvnw -o -q -pl unidbg-android install -DskipTests -Dgpg.skip=true
./mvnw -o -q -pl unidbg-android test-compile -Dmaven.test.skip=false -Dgpg.skip=true
CP="unidbg-android/target/test-classes:unidbg-android/target/classes:$(cat /tmp/cp.txt)"
"$JAVA_HOME/bin/java" -cp "$CP" com.vortexdbg.wf2.Wf2Spike
```

## Resultados

| # | Alvo (org.cf.*) | Resultado | Evidência |
|---|---|---|---|
| **T1** | `XORCrypt.encode/decode(String,String)` | ✅ **OK** | round-trip `"hello vortex-dbg"` correto; classe carregada pelo `VortexClassLoader` |
| **T2** | `MathCrypt.encode(int)->int[]` / `decode(int[])->int` | ✅ **OK** | `1337 -> [151,31,388739] -> 1337` (arg/retorno de array) |
| **T3** | `StringHolder.get(int)` (deobfuscação) | ⚠️ bloqueado | `NoClassDefFoundError: android/util/Base64` |

**Veredito:** o núcleo do A1 (executar Java do app off-device, invocar com args, obter
retorno real) está **provado**. O T3 — a máquina de deobfuscação de strings — depende de
`android.util.Base64`, isto é, da **camada de framework (WF4)**. Não é bloqueio do WF2; é
a fronteira natural para o WF4 (android-all.jar/Robolectric no classpath do app).

## Próximos
- **Integração ProxyClassFactory** (native/DvmClass -> classe real do app), conectando à FASE 0.
- **WF4:** pôr `android-all.jar` (Robolectric) no parent do `VortexClassLoader` para destravar
  T3 e qualquer classe do app que toque `android.*`.
