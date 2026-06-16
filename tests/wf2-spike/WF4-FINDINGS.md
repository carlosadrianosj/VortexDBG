# WF4 — Camada de Framework (Vortex-DBG / A1)

**Branch:** `002/wf4` (cópia de `001-begin`)
**Objetivo:** dar suporte às chamadas `android.*` das classes do app, para executar na
JVM host a lógica que toca o framework Android (o WF2 parou em
`NoClassDefFoundError: android/util/Base64`).

## Abordagem
Pôr o **android-all** da Robolectric (AOSP **real**, não o stub `android.jar`) no classpath
do app, junto dos `.class` extraídos. As classes do app que tocam `android.*` resolvem e
executam direto na JVM host.

- Artefato: `org.robolectric:android-all:13-robolectric-9030017` (~178MB, Android 13).
  Baixado em `~/tools/android-all/` (NÃO versionado). Re-obter:
  `curl -fsSL -o android-all-13-robolectric-9030017.jar https://repo1.maven.org/maven2/org/robolectric/android-all/13-robolectric-9030017/android-all-13-robolectric-9030017.jar`
- Uso: `new VortexClassLoader(appJar, androidAllJar)` — sem código novo; o `VortexClassLoader`
  já aceita múltiplas fontes.

## ⚠️ Finding crítico: JDK de build ≠ JDK de runtime
- O **fork compila só em JDK 8** (`com.vortexdbg.Module` vs `java.lang.Module`).
- Mas o **android-all-13 é bytecode Java 11** (class file 55) → `UnsupportedClassVersionError`
  no JDK 8.
- **Solução:** rodar o RUNTIME do Vortex num **JDK moderno (21+)**. O bytecode Java 8 do
  unidbg roda sem problema em JDK 21. Logo: **compila com JDK 8, executa com JDK 21+.**
  (JDK 21 usado: `/Applications/JEB-Pro/bin/runtime`.)

## Resultado (E2E Java + framework, off-device)
`StringHolder.get(int)` — que no WF2 falhava — agora **deobfusca as strings reais** do app
(o app combina XOR + AES + DES + Base64 + cache):

```
get(0) = Tell me of your homeworld, Usul.
get(1) = What do you call the mouse shadow on the second moon?
get(2) = Snow Crash
get(3) = Neuromancer
get(4) = The Hitchhiker's Guide to the Galaxy
get(5) = Stranger in a Strange Land
get(6) = Dune, lol
get(7) = secretMethod
```

**RESULTADO WF4: pass=1 fail=0.** A camada de framework destrava a execução real da
lógica do app na JVM host. É o payoff da A1: extrair (JEB/dex2jar) + executar/deobfuscar
sem device.

### Rodar
```bash
# compila com JDK 8:
JAVA_HOME=$(cat /tmp/jdk8_home.txt) ./mvnw -o -q -pl unidbg-android test-compile -Dmaven.test.skip=false -Dgpg.skip=true
# roda com JDK 21:
CP="unidbg-android/target/test-classes:unidbg-android/target/classes:$(cat /tmp/cp.txt)"
/Applications/JEB-Pro/bin/runtime/bin/java -cp "$CP" com.vortexdbg.wf4.Wf4Spike
```

## Escopo / pendências
- ✅ Camada de framework (android-all) + E2E **Java↔framework** (deobfuscação real).
- ⏳ E2E completo **native↔Java↔framework**: depende da integração `ProxyClassFactory`
  (DvmClass -> classe real do app) — folded com o WF3/integração (task adiada).
- Nota: `android-all` puro funciona para classes AOSP sem shadow (ex.: `Base64`, crypto).
  Classes que exigem runtime/shadows do Robolectric (lifecycle, Looper, resources) vão
  precisar do sandbox do Robolectric — escopo de aprofundamento do framework.
