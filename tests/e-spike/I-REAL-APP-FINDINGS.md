# I — Validação em App/Código de Produção Real (Vortex-DBG / A1)

**Branch:** `008/real-app-validation`

## Alvo real
`libttEncrypt.so` — biblioteca de **criptografia de produção do TikTok/ByteDance**
(ELF 32-bit ARM, stripped, com `JNI_OnLoad`/RegisterNatives). Função real:
`com.bytedance.frameworks.core.encrypt.TTEncryptUtils.ttEncrypt(byte[], int) -> byte[]`.

## O que foi validado
`IDemo` roda a função real por **dois caminhos** e compara:
- **oráculo:** chamada direta UniDBG `DvmClass.callStaticJniMethodObject("ttEncrypt([BI)[B", ...)`.
- **E-path:** `TTEncryptUtils.ttEncrypt(...)` no HOST (classe com método `native`),
  instrumentada pelo `VortexInstrumentingClassLoader` e roteada ao UniDBG.

```
[oráculo]  ttEncrypt(16x00) = 7463030000019fd0866aa0cbd0323933d2d2fc8c20ec
[E-path]   ttEncrypt(16x00) = 7463030000019fd0866aa0cbd0323933d2d2fc8c20ec
RESULTADO I: OK — oráculo == E-path
```

## Significado
- O Vortex emula **código nativo de produção real, endurecido** (não brinquedo).
- O caminho **E (host-Java → native emulado)** funciona em produção real, com `.so` 32-bit
  e RegisterNatives via `JNI_OnLoad` (via `VortexSession.callJniOnLoad(true)`).
- A `VortexSession` lida com app 32-bit + processName real (`com.qidian.dldl.official`).

## Cobertura de "real" por dimensão
| Dimensão | Artefato real validado |
|---|---|
| Native (.so) | **libttEncrypt.so (TikTok)** — emulação + E-path |
| Java do app | obfuscated-app (org.cf.*) — execução/deobfuscação na host JVM (WF2) |
| Framework | android-all (AOSP real) — `StringHolder` deobfusca (WF4) |

> Nota: um único APK exercitando native+java+framework **simultaneamente** não estava no
> corpus de teste; cada dimensão foi validada em artefato de produção real. Um alvo real
> com `.so` chamando Java endurecido + framework é o próximo passo de validação end-to-end
> (basta extrair as classes via JEB e apontar a `VortexSession`).
