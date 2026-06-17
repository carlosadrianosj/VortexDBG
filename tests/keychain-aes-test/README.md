# SecureVault (keychain AES) — quick MCP test

A tiny mixed Java + native app, used to try the Vortex-DBG MCP server end to end.

* **Java side** (`SecureVault`): frames `(account, secret)` into a 16 byte block, then adds a
  checksum tag and hex-encodes the result. Runs on the host JVM.
* **Native side** (`TTEncryptUtils.ttEncrypt`): ByteDance's real AES in `libttEncrypt.so`
  (32 bit ARM), run on the emulator. This is the same `.so` as `tests/tiktok-test`.

The token is `seal(account, secret) = tag : hex(AES(block))`, so it depends on BOTH sides.
Vortex-DBG emulates the native AES and runs the Java framing on the host, off-device.

## Files

```
src/com/example/aeskeychain/SecureVault.java              app Java (framing + tag + hex)
src/com/bytedance/frameworks/core/encrypt/TTEncryptUtils  app class declaring the native AES
AndroidManifest.xml                                       package com.example.aeskeychain
build.sh                                                  builds out/keychain-aes.{apk,jar}
out/keychain-aes.apk                                      signed APK: classes.dex + lib/armeabi-v7a/libttEncrypt.so
out/keychain-aes.jar                                      app classes for Vortex's VortexClassLoader
```

Rebuild the APK (needs Android SDK build-tools 34 + a JDK):

```bash
./build.sh
```

## Harnesses (under vortexdbg-android/src/test)

* `com.vortexdbg.aeskeychain.AesKeychainAuto` — automated: seals a few accounts and checks
  the output is well-formed and deterministic (Java framing + native AES). No MCP.
* `com.vortexdbg.aeskeychain.AesKeychainMcp` — the MCP playground (below).

## Quick MCP test

1. Run `AesKeychainMcp`. It loads `libttEncrypt.so` from the APK, resolves the app classes,
   registers a custom `seal` tool and the DVM/Java tool provider, and waits.
2. In its console type `mcp` to start the MCP server on `http://localhost:9239/sse`.
3. Connect an AI client:
   * Claude Code: `claude mcp add --transport sse vortexdbg http://localhost:9239/sse`
   * Cursor: add `{"mcpServers":{"vortexdbg":{"url":"http://localhost:9239/sse"}}}`
4. Drive it from the AI. Things to try:

   ```
   check_connection
   dvm_list_classes
   dvm_call_static {class:"com/bytedance/frameworks/core/encrypt/TTEncryptUtils",
                    method:"ttEncrypt([BI)[B", args:["00000000000000000000000000000000","16"]}
   list_exports {module_name:"libttEncrypt.so", filter:"ss_"}
   disassemble_symbol {module_name:"libttEncrypt.so", symbol_name:"ss_encrypt"}
   add_breakpoint_by_symbol {module_name:"libttEncrypt.so", symbol_name:"ss_encrypt"}
   seal {account:"alice", secret:"hunter2"}
   poll_events
   read_args
   ```

`dvm_call_static` returns the native AES result decoded by the DVM
(`... -> 7463030000019fd0866aa0cbd0323933d2d2fc8c20ec (22 bytes)`), and breaking on
`ss_encrypt` then `read_args` shows the key/length the native code received.
