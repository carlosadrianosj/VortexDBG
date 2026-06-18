<p align="center">
  <img src="icon/vortex-logo.png" alt="Vortex-DBG" width="400"/>
</p>

<h1 align="center">Vortex-DBG</h1>

<p align="center"><em>Emulate Android native libraries <b>and</b> DEX/Java classes, together, off-device.</em></p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue.svg" alt="License: Apache-2.0"></a>
  <img src="https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/platform-macOS%20%7C%20Linux-lightgrey?logo=linux&logoColor=white" alt="Platform: macOS | Linux">
  <img src="https://img.shields.io/badge/arch-ARM32%20%7C%20ARM64-success" alt="Arch: ARM32 | ARM64">
  <img src="https://img.shields.io/badge/backends-Unicorn2%20%7C%20Dynarmic%20%7C%20Hypervisor-orange" alt="Backends">
  <img src="https://img.shields.io/badge/JVM-8%20build%20%C2%B7%2021%2B%20run-red?logo=openjdk&logoColor=white" alt="JVM">
  <img src="https://img.shields.io/badge/MCP-AI%20assisted-brightgreen" alt="MCP">
</p>

Many times, when you are reverse-engineering an app, it is far more worthwhile to take
the native library or a given Java class and just **emulate** it than to rewrite it into a
white box reimplementation. Often that is enough to validate something first, and only then
decide whether it is worth the cost of translating it to another language. That is exactly
why Vortex-DBG was created.

## Documentation

**[www.vortexdbg.reverselabs.dev](https://www.vortexdbg.reverselabs.dev)**

In the documentation you can learn about the project, how to use it to build things **for
production**, and also how to use the **MCP** integration. This README is just a quick overview.

## How it works (production, in one picture)

```
   ┌───────────────────────────┐        ┌──────────────────────────────────────────────┐        ┌────────────────────────────┐
   │  Target app (.apk)        │        │                  Vortex-DBG                    │        │  The value you needed,     │
   │                           │  load  │   native .so   ⇄   host JVM   (JNI bridge)     │  call  │  off-device & automated:   │
   │  • classes.dex (Java)     │ ─────▶ │   on a CPU         runs the app's              │ ─────▶ │  • keychain / token        │
   │  • lib*.so (native, huge) │        │   emulator         DEX/Java classes            │        │  • signature / crypto out  │
   └───────────────────────────┘        └──────────────────────────────────────────────┘        └────────────────────────────┘
```

Say an app has a DEX class plus a giant, obfuscated native library that, together, compute
something you want — a keychain, a request signature, an encrypted token. Reverse-engineering all
of that and rewriting it into a white-box reimplementation is expensive and brittle. Instead,
Vortex-DBG loads the app's `.so` onto a CPU emulator and runs its DEX/Java classes on a real host
JVM, wired together by a bidirectional JNI bridge, so you can simply **call the function and get the
value** — batched and off-device, with no rooted phone in the loop.

## MCP tools

Beyond production, Vortex-DBG is also for **experimentation and research through MCP**. It already
ships with a large set of MCP tools, so an AI client (Claude Code, Cursor, or any MCP client) can
drive the emulator for you: poke the native (ARM) side and the Dalvik/Java (DVM) side, set
breakpoints, follow the JNI bridge, and call functions — all by conversation.

Quick overview of what is available (each group has runnable, worked examples under
[`tests/MCP/`](tests/MCP)):

| Group | What the tools do | Try it in |
|---|---|---|
| **Native: modules & symbols** | list modules, exports, find symbols, threads | [`tests/MCP/01app`](tests/MCP/01app) |
| **Native: registers & disasm** | read/write registers, disassemble, assemble, read args | [`tests/MCP/01app`](tests/MCP/01app) |
| **Native: memory** | read/write/search memory, typed reads, pointers, alloc, patch | [`tests/MCP/01app`](tests/MCP/01app) · [`04app`](tests/MCP/04app) |
| **Native: breakpoints & tracing** | breakpoints, step in/over/out, code/read/write traces, callstack | [`tests/MCP/01app`](tests/MCP/01app) |
| **Native: function calls** | call a function by address or by module+symbol | [`tests/MCP/01app`](tests/MCP/01app) · [`03app`](tests/MCP/03app) |
| **DVM: class & method introspection** | list/search classes, hierarchy, describe methods/fields, DEX surface | [`tests/MCP/01app`](tests/MCP/01app) · [`02app`](tests/MCP/02app) |
| **DVM: calling** | call static/instance Java methods, oracle, fuzz | [`tests/MCP/01app`](tests/MCP/01app) |
| **DVM: objects & fields** | create/inspect objects, read arrays, read/write fields, std::string | [`tests/MCP/03app`](tests/MCP/03app) · [`04app`](tests/MCP/04app) |
| **DVM: native↔Java bridge** | resolve native handles, JNI trace / mock / break, RegisterNatives | [`tests/MCP/02app`](tests/MCP/02app) |

Example, in one breath: say you want to emulate a function from a banking app. You load its `.so`,
ask the AI to `add_breakpoint_by_symbol`, trigger the call, then use `read_args` / `dvm_call_static`
/ `dvm_trace_jni` to watch the native↔Java conversation and pull out what it computes. That is just
the quick view — for the full workflow and the complete tool reference, read the
[documentation](https://www.vortexdbg.reverselabs.dev).

The [`tests/MCP/`](tests/MCP) folder has four demo apps and a one-command `run-all.sh` that drives
every tool against them, so you can see each one work end to end.

## Thanks

- [Vortex-DBG](https://github.com/carlosadrianosj/VortexDBG)
- [unicorn](https://github.com/zhkl0228/unicorn)
- [dynarmic](https://github.com/MerryMage/dynarmic)
- [HookZz](https://github.com/jmpews/Dobby)
- [xHook](https://github.com/iqiyi/xHook)
- [AndroidNativeEmu](https://github.com/AeonLucid/AndroidNativeEmu)
- [usercorn](https://github.com/lunixbochs/usercorn)
- [keystone](https://github.com/keystone-engine/keystone)
- [capstone](https://github.com/aquynh/capstone)
- [idaemu](https://github.com/36hours/idaemu)
- [jelf](https://github.com/fornwall/jelf)
- [whale](https://github.com/asLody/whale)
- [mman-win32](https://github.com/mcgarrah/mman-win32)

## License

[Apache License 2.0](LICENSE). Vortex-DBG is a derivative work based on the architecture of
[UniDBG](https://github.com/zhkl0228/unidbg) (Apache-2.0). See [NOTICE](NOTICE).
