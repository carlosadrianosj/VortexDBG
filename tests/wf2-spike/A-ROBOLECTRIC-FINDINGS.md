# A — Camada de Framework / Robolectric Sandbox (Vortex-DBG / A1)

**Branch:** `005/robolectric-sandbox`

## O que foi entregue (produção)
- **`com.vortexdbg.app.VortexFramework`** — ponto único que monta a camada de framework
  Android para a execução das classes do app na host JVM. Hoje sobre o **android-all**
  (AOSP real). API: `fromAndroidAll(jar)`, `newAppClassLoader(appSources...)`.
- `VortexFrameworkDemo` — prova: a deobfuscação do app (`StringHolder.get`) roda pela API.

## Cobertura atual (android-all puro)
O android-all contém implementações AOSP **reais**. Para a classe de código que mais
importa em RE (cripto, strings, ofuscação, parsing, coleções) isso **já executa de
verdade** na host JVM — provado: `android.util.Base64` → deobfuscação real das strings.
Cobre, sem shadow: `Base64`, `TextUtils` (maioria), `JSONObject/JSONArray`, `SparseArray`,
`android.net.Uri` (parsing), helpers de `android.text`, etc.

## O que o android-all puro NÃO resolve (precisa de instrumentação + shadows)
Classes `android.*` cujo comportamento depende de estado/serviços do runtime Android:
`Looper`/`Handler`/`MessageQueue`, `Context`/`Application`, lifecycle de `Activity`,
`Resources`/`AssetManager`, `Parcel`, `SharedPreferences`, `SQLiteDatabase`,
`View`/`Bitmap`/`Canvas`. Os corpos desses métodos no android-all são stubs/no-ops ou
dependem de nativo — só "funcionam" sob a instrumentação do Robolectric.

## Design da integração completa do sandbox Robolectric (esforço separado)
Bootstrap programático (fora do JUnit) é viável — classes-chave (no clone
`tools-benchmark/repos/robolectric/sandbox/...`):
- `InstrumentationConfiguration.newBuilder().addInstrumentedPackage("android.")…build()`
- `new SandboxClassLoader(config, new UrlResourceProvider(androidAllUrls), new ClassInstrumentor(new ShadowDecorator()))`
  → reescreve via ASM os corpos `android.*` para `invokedynamic`.
- `ShadowMap` (a partir de `shadows-framework`) + `new ShadowWrangler(shadowMap, …, interceptors)` como `ClassHandler`.
- `Sandbox.configure(classHandler, interceptors)` para ligar o bootstrap do invokedynamic
  (`InvokeDynamicSupport`) ao ShadowWrangler.
- Ambiente Android (Application/Context/recursos) é montado pelo
  `AndroidTestEnvironment` — necessário para lifecycle/resources reais.

**Por que é uma fase à parte:** exige (a) dependência Robolectric + ~dezenas de
transitivas, (b) fiação interna sensível a versão, (c) que as classes do app sejam
carregadas PELO sandbox classloader (instrumentado), invertendo o modelo atual
(VortexClassLoader com framework no parent). É integração de porte, não um helper.

## Recomendação
1. **Já usável:** `VortexFramework` (android-all) cobre o caso comum de RE (cripto/strings/
   lógica) — suficiente para o alvo principal do Vortex.
2. **Incremento controlado:** antes do Robolectric completo, suportar **shadows pontuais**
   do Vortex para as poucas classes que um alvo específico exigir (Log/Looper mínimos),
   carregadas com precedência sobre o android-all.
3. **Robolectric completo:** integração dedicada (próxima iteração de framework), seguindo
   o design acima, quando um alvo exigir lifecycle/recursos de verdade.

> Status A: **camada de framework produtizada e funcional para o caso comum**; sandbox
> Robolectric completo especificado e escopado como integração dedicada subsequente.
