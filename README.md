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

Vortex-DBG lets you emulate **both** sides of an app at once. It runs native code (`.so`,
ARM/ARM64) on a CPU emulator (Unicorn2, Dynarmic, or the Apple hypervisor) while running the
app's **Dalvik/Java (DEX) classes on a real host JVM**, with a **bidirectional JNI bridge**
between them. So the mixed native and Java logic an app actually uses, the kind you normally
can only exercise on a device, can be reproduced and **automated off-device**.

Vortex-DBG is **based on the architecture of [UniDBG](https://github.com/zhkl0228/unidbg)**,
a great but experimental project. The inspiration was to take that experimental foundation
and turn it into something built **for production**: optimized, rewritten end to end in
**Kotlin**, focused on Android, with the native plus DEX fusion validated against real apps
(see the examples below).

> Educational / research tool for **authorized** reverse engineering. Use at your own risk.

## What it does

- **Native emulation** of Android `.so` for ARM32 / ARM64. Backends: [unicorn2](https://github.com/zhkl0228/unicorn), [dynarmic](https://github.com/MerryMage/dynarmic) (fast), Apple M-series hypervisor.
- **JNI Invocation API** emulation (JavaVM / JNIEnv), so `JNI_OnLoad` and native/Java calls work.
- **The fusion**: run the app's Java/DEX classes on the **host JVM** with a JNI bridge, so native (emulated) and Java (host) call each other in **both directions**.
- Inline hooks ([Dobby](https://github.com/jmpews/Dobby)/HookZz), Android import hooks ([xHook](https://github.com/iqiyi/xHook)).
- syscall emulation, memory-leak detection, a thread-safe worker pool.
- An **[MCP](https://modelcontextprotocol.io/) server** for AI-assisted debugging (Cursor and other AI tools).

## Examples

Two end-to-end tests live under `tests/`, both run **fully off-device** on the emulator.

### 1. Keychain APK: mixed Java/native, automated (`tests/keychain-test/`)

A tiny Android app whose key derivation is genuinely **mixed and bidirectional**.
`KeyChain.generate(account)` is a **native** method (the mixing runs in the `.so`), and the
native code calls **back** into the app's Java (`salt()` and `hex()`). Vortex-DBG pulls
`libkeychain.so` straight out of the signed `keychain.apk` (emulated ARM64), runs the app's
Java on the host JVM, and automates keychain generation for any account:

```
alice        -> 945cc2e13b7489ff
bob          -> 8e56a85abf7489ff
carol@corp   -> 985288e909bf0765
user-12345   -> bc35bacb8c59a6ce
```

Harness: [`KeyChainAuto.java`](vortexdbg-android/src/test/java/com/vortexdbg/keychain/KeyChainAuto.java).
App + APK: [`tests/keychain-test/`](tests/keychain-test).

### 2. TikTok `libttEncrypt`: real native crypto (`tests/tiktok-test/`)

ByteDance's real AES-based `ttEncrypt`. Vortex-DBG loads the `.so`, runs `JNI_OnLoad` and
`init_array`, and calls the encryption, entirely emulated:

```
ttEncrypt(16x 0x00) = 7463030000019fd0866aa0cbd0323933d2d2fc8c20ec
```

Harness: [`TikTokAuto.java`](vortexdbg-android/src/test/java/com/bytedance/frameworks/core/encrypt/TikTokAuto.java).

## MCP Debugger (AI Integration)

Vortex-DBG supports the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/)
for AI-assisted debugging. When the debugger is active, type `mcp` in the console to start
an MCP server that AI tools (e.g. Cursor) can connect to.

### Quick Start

Two operating modes:

**Mode 1, Breakpoint Debug.** Attach the debugger and run your code. When a breakpoint is
hit, `Breaker.debug()` pauses the emulator, so you can type `mcp` in the console to start the
MCP server and let AI assist with analysis. All debugging tools are available (registers,
memory, disassembly, stepping, tracing, etc). After resuming, the next breakpoint pauses
again; once execution completes, the process exits and MCP shuts down.

```java
Debugger debugger = emulator.attach();
debugger.addBreakPoint(address);
// run your emulation logic; the debugger pauses when the breakpoint is hit
```

**Mode 2, Custom Tools (repeatable).** Use `McpToolkit` to register custom tools and let AI
re-run target functions with different parameters. The native library is loaded once; after
each execution the process stays alive and MCP remains active for the next run.

```java
McpToolkit toolkit = new McpToolkit();
toolkit.addTool(new McpTool() {
    @Override public String name() { return "encrypt"; }
    @Override public String description() { return "Run encryption"; }
    @Override public String[] paramNames() { return new String[]{"input"}; }
    @Override public void execute(String[] params) {
        String input = params.length > 0 ? params[0] : "default";
        // call encryption with input
    }
});
toolkit.run(emulator.attach());
```

When the debugger breaks, type `mcp` (or `mcp 9239` to specify a port) in the console.
Then add to your Cursor MCP settings:

```json
{
  "mcpServers": {
    "vortexdbg-mcp-server": {
      "url": "http://localhost:9239/sse"
    }
  }
}
```

### Available MCP Tools

**Status & Info**

| Tool | Description |
|------|-------------|
| `check_connection` | Emulator status: architecture, backend capabilities, isRunning, loaded modules |
| `list_modules` / `get_module_info` | List loaded modules; detail incl. exported symbol count and dependencies |
| `list_exports` | List exported/dynamic symbols of a module with optional filter and C++ demangling |
| `find_symbol` | Find symbol by name or find nearest symbol at address |
| `get_threads` | List all threads/tasks in the emulator |

**Registers & Disassembly**

| Tool | Description |
|------|-------------|
| `get_registers` / `get_register` / `set_register` | Read/write CPU registers |
| `disassemble` | Disassemble at address (branch targets auto-annotated with symbol names) |
| `assemble` | Assemble instruction text to machine code |
| `get_callstack` | Get current call stack (backtrace) |

**Memory**

| Tool | Description |
|------|-------------|
| `read_memory` / `write_memory` | Read/write raw memory bytes |
| `read_string` / `read_std_string` | Read C string or C++ std::string (with SSO detection) |
| `read_pointer` | Read pointer chain with symbol resolution |
| `read_typed` | Read memory as typed values (int8 to int64, float, double, pointer) |
| `search_memory` | Search memory for byte patterns with scope/permission filters |
| `list_memory_map` | List all memory mappings with permissions |
| `allocate_memory` / `free_memory` / `list_allocations` | Allocate (malloc/mmap), free, and track memory blocks |
| `patch` | Write assembled instructions to memory |

**Breakpoints & Execution**

| Tool | Description |
|------|-------------|
| `add_breakpoint` / `add_breakpoint_by_symbol` / `add_breakpoint_by_offset` | Add breakpoints by address, symbol, or module+offset |
| `remove_breakpoint` / `list_breakpoints` | Remove or list breakpoints |
| `continue_execution` | Resume execution; use `poll_events` to wait for breakpoint_hit / execution_completed |
| `step_over` / `step_into` / `step_out` | Step over, into (N instructions), or out of a function |
| `next_block` | Break at next basic block (Unicorn only) |
| `step_until_mnemonic` | Break at next instruction matching mnemonic, e.g. `bl`, `ret` (Unicorn only) |
| `poll_events` | Poll for breakpoint_hit, execution_completed, trace events |

**Tracing**

| Tool | Description |
|------|-------------|
| `trace_code` | Trace instructions with register read/write values |
| `trace_read` / `trace_write` | Trace memory reads/writes in an address range |

**Function Calls**

| Tool | Description |
|------|-------------|
| `call_function` | Call native function by address with typed args (hex, string, bytes, null) |
| `call_symbol` | Call exported function by module + symbol name, e.g. `libc.so` + `malloc` |

### Custom MCP Tools

Use `McpToolkit` to register custom tools, each implementing the `McpTool` interface.
By the time a tool runs, the native library is fully loaded (`JNI_OnLoad` / entry point
already executed), so the code inside each tool's `execute()` is the target function logic
to analyze. AI can set breakpoints and traces before triggering a custom tool, then inspect
execution results across different inputs without restarting the process.

```java
DalvikModule dm = vm.loadLibrary(new File("libtmessages.29.so"), true);
dm.callJNI_OnLoad(emulator);
cUtilities = vm.resolveClass("org/telegram/messenger/Utilities");

McpToolkit toolkit = new McpToolkit();
toolkit.addTool(new McpTool() {
    @Override public String name() { return "aesCbc"; }
    @Override public String description() { return "Run AES-CBC encryption on input data"; }
    @Override public String[] paramNames() { return new String[]{"input"}; }
    @Override public void execute(String[] params) {
        byte[] input = params.length > 0 ? params[0].getBytes() : new byte[16];
        aesCbcEncryptionByteArray(input);
    }
});
toolkit.run(emulator.attach());
```

Once the MCP server is started, AI can call these tools via MCP to run emulations with
custom parameters, set breakpoints, trace execution, and inspect results, all without
restarting the process.

> **Low-level API**: you can also use `Debugger.addMcpTool()` + `Debugger.run(DebugRunnable)`
> directly for full control. `McpToolkit` is a higher-level wrapper.

## Memory Leak Detection

Track guest-side allocations (mmap/munmap/brk) to detect leaks in emulated native code.
Use `try-with-resources`: tracking starts on creation, and the leak report is printed on close.

```java
try (MemoryTracker tracker = emulator.traceMemoryLeaks()) {
    module.callFunction(emulator, "targetFunction", arg1, arg2);
}
```

Each leaked block includes the guest ARM backtrace (module+offset+symbol) and the host
Java stack trace.

## Worker Pool

A thread-safe object pool for reusing emulator instances across threads, avoiding the
overhead of repeated initialization (lazy init, max limit, idle cleanup, min-idle).

```java
WorkerPool pool = WorkerPoolFactory.create(MyWorker::new);
try (WorkerLoan<MyWorker> loan = pool.borrow(1, TimeUnit.MINUTES)) {
    if (loan != null) {
        byte[] result = loan.get().doWork(input);
    }
}
pool.close();
```

## Platforms

Vortex-DBG currently runs on **macOS** and **Linux** only (the native backends ship for
`osx_arm64`, `osx_64`, `linux_arm64` and `linux_64`). **Windows is not supported.**

## Build

```bash
./mvnw -DskipTests package   # JDK 8 to compile; run on JDK 21+
```

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
