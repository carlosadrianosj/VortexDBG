# vortexdbg-kotlin

Camada **Kotlin** do Vortex-DBG (Fase 1 da integração). Princípio inviolável:
**o core fica em Java** (mergeability com o upstream do unidbg); o Kotlin entra só aqui,
na ergonomia sobre a API Java e em features novas.

## Por que um módulo separado
O `kotlin-maven-plugin` vive **só neste POM**. O pom raiz muda o mínimo (1 `<module>` + a
property `kotlin.version`); os POMs espelhados do upstream (`unidbg-*`, `backend/*`) **não
são tocados** — o merge com o unidbg continua limpo.

## Conteúdo (piloto)
- `VortexDsl.kt` — DSL type-safe `vortexSession { ... }` sobre `VortexSession.Builder` (Java)
  + extensão `invokeStaticAs<T>(...)` com null-safety e generics reificados.
- `KotlinDslDemo.kt` — prova de runtime: abre a sessão via DSL e roda o `libttEncrypt` real.

## Build & run (JDK 8 compila; o core continua Java)
```bash
JAVA_HOME=$(cat /tmp/jdk8_home.txt) ./mvnw -pl vortexdbg-kotlin install -Dgpg.skip=true
KSTDLIB=~/.m2/repository/org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar
CP="vortexdbg-kotlin/target/classes:unidbg-android/target/classes:$KSTDLIB:$(cat /tmp/cp.txt)"
$JAVA_HOME/bin/java -cp "$CP" com.vortexdbg.kotlin.KotlinDslDemo
# -> ttEncrypt(16x00) = 7463030000019fd0866aa0cbd0323933d2d2fc8c20ec  (Kotlin DSL roda o emulador)
```

## Próximas fases (do plano em ideas/kotlin-integration)
- Fase 2: `VortexInvoker` → `sealed VortexResult`; `VortexCli` com Clikt; value classes p/ handles JNI.
- Fase 3: coroutines FORA do hot-path — eventos do debugger via `callbackFlow`, RPC do TEE
  com suspend, frontend interativo. **Nunca** paralelizar a emulação (single-thread).
- Detekt (lint) no módulo.
