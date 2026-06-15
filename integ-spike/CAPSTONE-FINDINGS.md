# CAPSTONE — Stack A1 Completo (Vortex-DBG)

**Branch:** `004/integration` (cópia de `003/wf3`)
**Objetivo:** provar o stack inteiro **native ↔ app-Java ↔ framework** numa só execução,
juntando WF1+FASE0+WF2+WF3+WF4.

## Montagem
- `integ-spike/integ.c` → `libinteg.so` (ARM64, NDK 26): funções nativas que fazem
  `FindClass`/`CallStaticObjectMethod` em classes do app.
- `IntegSpike` (test): emulador UniDBG (Unicorn2) + `VortexClassLoader(appJar, androidAll)`
  + `vm.setDvmClassFactory(new ProxyClassFactory(vortexClassLoader))`.
  O `ProxyClassFactory` resolve os `FindClass` do nativo contra as classes REAIS do app
  (carregadas na JVM host), e o `ProxyJni` invoca os métodos por reflexão.

## Resultado (2/2)
```
backend=com.vortexdbg.arm.backend.Unicorn2Backend
[T1 native->XORCrypt.encode]   native="KG"  host="KG"   OK     (native -> app)
[T2 native->StringHolder.get(0)->framework] = "Tell me of your homeworld, Usul."  OK
                                                            (native -> app -> framework)
```
- **T1:** `.so` emulado chama `org.cf.crypto.XORCrypt.encode(String,String)` real na JVM
  host; resultado idêntico à invocação direta no host (oráculo).
- **T2:** `.so` emulado chama `org.cf.obfuscated.StringHolder.get(int)`, que usa
  `android.util.Base64` (framework android-all) e devolve a string deobfuscada real —
  o stack completo numa única chamada nativa.

### Rodar (compila JDK 8, roda JDK 21)
```bash
export JAVA_HOME=$(cat /tmp/jdk8_home.txt)
./mvnw -o -q -pl unidbg-android test-compile -Dmaven.test.skip=false -Dgpg.skip=true
CP="unidbg-android/target/test-classes:unidbg-android/target/classes:$(cat /tmp/cp.txt)"
/Applications/JEB-Pro/bin/runtime/bin/java \
  --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED \
  -cp "$CP" com.vortexdbg.integ.IntegSpike
```

## Significado
A "Arquitetura de Fusão" da proposta original está **demonstrada de ponta a ponta**:
o Vortex-DBG manipula e executa, num único processo hermético e off-device, **tanto a
camada nativa (.so ARM/ARM64) quanto as classes Dalvik/DEX do app** (incl. a máquina de
ofuscação), com a fronteira JNI e a camada de framework funcionando. Substitui o fluxo
LSPosed/Zygote num device físico.

## Limites conhecidos (próximos aprofundamentos)
- `android-all` cru cobre classes AOSP sem shadow (Base64, crypto, coleções). Classes que
  exigem o runtime do Robolectric (lifecycle de Activity, Looper, recursos, Parcel) vão
  precisar do sandbox/instrumentação do Robolectric.
- TEE/attestation seguem fora (RPC para device real — estratégia do relatório).
- Front-end de extração: dex2jar aqui; JEB para apps protegidos (mesmo contrato `.class`).
