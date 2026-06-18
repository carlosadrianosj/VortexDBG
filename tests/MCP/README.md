# Vortex-DBG MCP test suite

A self-contained playground that exercises **every Vortex-DBG MCP tool** (both the native/ARM
side and the Dalvik/Java side) against one small, deliberately "complete" Android app. It doubles
as a tutorial: read [`run-all.sh`](run-all.sh) top to bottom and you have a worked example of how
to drive each tool, and [`run-all.log`](run-all.log) is the captured output of a full run.

- **86 MCP tools** are covered: **44 native (ARM)** + **42 Dalvik/Java (DVM)**.
- One driver script calls them in a sensible order, chaining object handles where needed.
- Everything is plain `curl` against the MCP server, so you can copy any call into your own client.

---

## 1. How MCP works here (who is the server, who is the client)

```
   your AI client                         Vortex-DBG (the MCP server)
  (Claude Code / Cursor / curl)           inside the McpDemoHarness JVM
        |                                          |
        |  tools/list, tools/call (JSON-RPC) over  |
        |  HTTP+SSE at http://localhost:9239/sse   |
        | <--------------------------------------> |
                                                   |
                            +----------------------+----------------------+
                            |                                             |
                   NATIVE (ARM) side                          DALVIK / JAVA (DVM) side
              libvault.so on Unicorn2                  app classes on the host JVM
              (registers, memory, disasm,              (classes, objects, fields, JNI
               breakpoints, traces, calls)              hooks, DEX surface, calls)
```

**Vortex-DBG is the MCP server, not the client.** You start it (the harness), type `mcp` in its
console, then point any MCP client at `http://localhost:9239/sse`. The client calls tools; the
server drives the emulated app. In this suite the "client" is just `curl` (see the `call()` helper
in `run-all.sh`), which is the simplest way to see exactly what each tool receives and returns.

---

## 2. The demo app (`com.example.mcpdemo`)

A tiny app whose surface intentionally covers every shape an MCP tool needs to act on.

| Element | Kind | Exists so you can test |
|---|---|---|
| `Vault.seal(account, secret)` | **static native**, calls back `Device.salt()` / `Device.hex()` | `dvm_call_static`, JNI hooks (`dvm_trace_jni`/`dvm_mock_jni`/`dvm_break_on_jni`), native breakpoints |
| `Vault.transform(input)` | **instance native** | `dvm_call_instance` |
| `Vault.label`, `Vault.counter` | **instance fields** | `dvm_read_field` / `dvm_set_field` (instance) |
| `Vault(label)` | **constructor** | `dvm_new_object` |
| `Device.API_LEVEL`, `Device.BUILD_TAG` | **static fields** | `dvm_read_field` (static) |
| `Device.salt()` / `Device.hex()` | **Java methods called back from native** | what the JNI hooks observe/mock |
| `libvault.so` exports | `Java_..._seal`, `Java_..._transform` | `list_exports`, `find_symbol`, `disassemble_symbol`, `add_breakpoint_by_symbol` |

Source: [`src/com/example/mcpdemo/`](src/com/example/mcpdemo) (Java) and [`jni/vault.c`](jni/vault.c) (native).

Why "mixed": `seal` runs native code **and** calls back into Java, so a single call crosses the
JNI bridge in both directions — exactly the fusion Vortex-DBG reproduces off-device, and exactly
what makes the hook/trace tools observable.

---

## 3. Build and run

### Build the app (APK + jar + native lib)

```bash
tests/MCP/build.sh
```

Produces `tests/MCP/out/`: `mcpdemo.apk` (signed; bundles `classes.dex` + `lib/arm64-v8a/libvault.so`),
`mcpdemo.jar` (the app's Java classes for Vortex's `VortexClassLoader`), and `libvault.so`.
Requires the Android SDK build-tools 34, NDK 21 (arm64 clang) and a JDK. See the script header.

### Run the whole suite (one command)

```bash
tests/MCP/run-all.sh
```

It compiles the harness if needed, boots it, starts the MCP server, calls every tool, and writes
[`run-all.log`](run-all.log). Use this both to verify and to learn.

### Run the harness by hand (to connect your own client)

```bash
# from the repo root, with the project already compiled (mvn compile):
JEB=/Applications/JEB-Pro/bin/runtime
CP=vortexdbg-api/target/classes:vortexdbg-android/target/classes:vortexdbg-android/target/test-classes
for b in dynarmic unicorn2 kvm hypervisor; do CP="$CP:backend/$b/target/classes"; done
CP="$CP:$(cat /tmp/mcp_deps.txt)"   # third-party deps (see run-all.sh for how this is produced)

$JEB/bin/java -cp "$CP" com.vortexdbg.mcpdemo.McpDemoHarness
# then type:  mcp
```

Harness source: [`harness/McpDemoHarness.java`](harness/McpDemoHarness.java). It creates the VM
**with the APK** (`createDalvikVM(apk)`) so `dvm_dex_surface` can read the embedded `classes.dex`,
loads `libvault.so`, registers the `DvmMcpTools` provider, and exposes two console triggers
(`run seal <account> <secret>`, `run transform <text>`) for breakpoint demos.

### Connect a real MCP client

* **Claude Code:** `claude mcp add --transport sse vortexdbg http://localhost:9239/sse`
* **Cursor:** add `{"mcpServers":{"vortexdbg":{"url":"http://localhost:9239/sse"}}}`

Then ask the AI to call the tools below.

---

## 4. Tool reference — NATIVE (ARM) side (44)

Drives `libvault.so` on the CPU emulator. Most work any time; the breakpoint/step/trace tools work
once execution is paused (see the walkthrough). Addresses are hex strings; `args` for calls are
typed strings: `"0x10"` (hex int), `"s:text"` (C string), `"b:48656c6c6f"` (hex bytes), `"null"`.

**Status & modules**

| Tool | Purpose | Example arguments |
|---|---|---|
| `check_connection` | arch / backend / state / loaded modules | `{}` |
| `list_modules` | list loaded `.so` (optional name filter) | `{"filter":"vault"}` |
| `get_module_info` | base / size / exports / deps | `{"module_name":"libvault.so"}` |
| `list_exports` | exported symbols (optional filter) | `{"module_name":"libvault.so"}` |
| `find_symbol` | resolve a symbol, or nearest to an address | `{"module_name":"libvault.so","symbol_name":"Java_com_example_mcpdemo_Vault_seal"}` |
| `get_threads` | emulator threads/tasks | `{}` |

**Registers & disassembly**

| Tool | Purpose | Example arguments |
|---|---|---|
| `get_registers` | all general registers | `{}` |
| `get_register` | one register | `{"name":"X0"}` |
| `set_register` | write a register | `{"name":"X0","value":"0xdead"}` |
| `disassemble` | disasm at an address | `{"address":"0x1200056c","count":"6"}` |
| `disassemble_symbol` | disasm a function by symbol | `{"module_name":"libvault.so","symbol_name":"Java_com_example_mcpdemo_Vault_seal","count":"8"}` |
| `assemble` | text -> machine code | `{"assembly":"mov x0, #1"}` |
| `read_args` | decode the calling-convention arg registers | `{"count":"4"}` |

**Memory**

| Tool | Purpose | Example arguments |
|---|---|---|
| `read_memory` | hex dump | `{"address":"0x121d7000","size":"16"}` |
| `write_memory` | write raw hex | `{"address":"0x121d7000","hex_bytes":"01020304"}` |
| `write_string` | write a NUL-terminated C string | `{"address":"0x121d7000","text":"hi-mcp"}` |
| `read_string` | read a C string | `{"address":"0x121d7000"}` |
| `read_std_string` | read a C++ `std::string` (libc++ SSO) | `{"address":"0x..."}` *(no std::string in this app; use on a real target)* |
| `read_pointer` | follow a pointer chain | `{"address":"0x121d7000"}` |
| `read_typed` | typed view (int8..int64/float/double/pointer) | `{"address":"0x121d7000","type":"int32","count":"2"}` |
| `search_memory` | find a hex pattern or string | `{"pattern":"hi-mcp","type":"string"}` |
| `list_memory_map` | mapped regions | `{}` |
| `patch` | assemble + write | `{"address":"0x121d7000","assembly":"nop"}` |
| `allocate_memory` / `free_memory` / `list_allocations` | scratch buffers | `{"size":"16","data":"41424344"}` |

**Breakpoints & execution**

| Tool | Purpose | Example arguments |
|---|---|---|
| `add_breakpoint` | breakpoint at an address | `{"address":"0x1200056c"}` |
| `add_breakpoint_by_symbol` | breakpoint at a symbol | `{"module_name":"libvault.so","symbol_name":"Java_com_example_mcpdemo_Vault_seal"}` |
| `add_breakpoint_by_offset` | breakpoint at module+offset (IDA/Ghidra offsets) | `{"module_name":"libvault.so","offset":"0x7e0"}` |
| `remove_breakpoint` / `list_breakpoints` | manage breakpoints | `{"address":"0x1200056c"}` |
| `continue_execution` | resume | `{}` |
| `step_over` / `step_into` / `step_out` | step | `{"count":"2"}` (step_into) |
| `next_block` | run to next basic block (Unicorn) | `{}` |
| `step_until_mnemonic` | run to next matching mnemonic (Unicorn) | `{"mnemonic":"bl"}` |
| `poll_events` | fetch queued events (breakpoint_hit, trace, completed) | `{"timeout_ms":"4000"}` |

**Tracing**

| Tool | Purpose | Example arguments |
|---|---|---|
| `trace_code` | trace instruction execution in a range | `{"begin":"0x1200056c","end":"0x120005a0"}` |
| `trace_read` / `trace_write` | trace memory reads/writes in a range | `{"begin":"0x...","end":"0x..."}` |

**Stack & calls**

| Tool | Purpose | Example arguments |
|---|---|---|
| `get_callstack` | backtrace | `{}` |
| `call_function` | call a native function by address | `{"address":"0x...","args":["s:vortex"]}` |
| `call_symbol` | call an exported function by module+symbol | `{"module_name":"libc.so","symbol_name":"strlen","args":["s:mcp"]}` |

---

## 5. Tool reference — DALVIK / JAVA (DVM) side (42)

Drives the host-JVM Dalvik VM. Register the provider with
`debugger.addMcpToolProvider(new DvmMcpTools(emulator, vm))` (the harness does this).
Object handles are JNI hashes (decimal or `0x`-hex); object/array arguments may be passed as
`@0x<handle>` to reuse an existing object.

**Class & method introspection**

| Tool | Purpose | Example arguments |
|---|---|---|
| `dvm_list_classes` | resolved DVM classes | `{}` |
| `dvm_search_classes` | search classes by name | `{"query":"Vault"}` |
| `dvm_class_hierarchy` | superclass chain + interfaces | `{"class":"com/example/mcpdemo/Vault"}` |
| `dvm_describe_class` | methods/fields the VM has touched | `{"class":"com/example/mcpdemo/Vault"}` |
| `dvm_describe_method` | decode a JNI method signature | `{"class":"com/example/mcpdemo/Vault","method":"seal(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"}` |
| `dvm_list_native_registrations` | RegisterNatives bindings per class | `{}` |

**DEX static surface** (reads the embedded `classes.dex`)

| Tool | Purpose | Example arguments |
|---|---|---|
| `dvm_dex_surface` | list/search DEX classes, methods or strings | `{"kind":"method","query":"seal"}` (add `"path":"...apk"` if the VM has no bundled APK) |

**Calling through the bridge**

| Tool | Purpose | Example arguments |
|---|---|---|
| `dvm_call_static` | call a static (native-registered) method | `{"class":"com/example/mcpdemo/Vault","method":"seal(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;","args":["alice","hunter2"]}` |
| `dvm_call_instance` | call an instance method on a handle | `{"hash":"0x...","method":"transform(Ljava/lang/String;)Ljava/lang/String;","args":["hello"]}` |
| `dvm_oracle` | call + assert against an expected value | `{"class":"...","method":"...","args":[...],"expect":"...","match":"contains"}` |
| `dvm_fuzz_method` | batch-call over inputs, tabulate byte variance | `{"class":"...","method":"...","inputs":["alice","bob"],"arg_index":0,"fixed_args":["pw"]}` |

**Object lifecycle**

| Tool | Purpose | Example arguments |
|---|---|---|
| `dvm_make_object` | create a String/bytes/array object -> handle | `{"type":"string","value":"hello-vortex"}` |
| `dvm_new_object` | construct an app object (host-side) -> handle | `{"class":"com/example/mcpdemo/Vault","method":"<init>(Ljava/lang/String;)V","args":["mylabel"]}` |
| `dvm_new_array_object` | build an `Object[]` from handles/strings -> handle | `{"elements":["@0x...","x"]}` |
| `dvm_pin_ref` / `dvm_release_ref` | promote to / drop from the global ref table | `{"hash":"0x..."}` |

**Inspection**

| Tool | Purpose | Example arguments |
|---|---|---|
| `dvm_list_objects` | live JNI refs (local + global) | `{}` |
| `dvm_get_object` | class + value of a handle | `{"hash":"0x..."}` |
| `dvm_inspect_object` | deep view: scope, refCount, value, length | `{"hash":"0x..."}` |
| `dvm_read_string` | the Java String of a StringObject handle | `{"hash":"0x..."}` |
| `dvm_read_array` | typed contents of an array handle | `{"hash":"0x..."}` |
| `dvm_to_string` | best-effort String rendering of a handle | `{"hash":"0x..."}` |
| `dvm_read_field` | read a static or instance field | static: `{"class":"...Device","field":"API_LEVEL","type":"I"}` · instance: `{"field":"label","type":"Ljava/lang/String;","target_hash":"0x..."}` |
| `dvm_set_field` | write a static or instance field | `{"target_hash":"0x...","field":"counter","type":"I","value":"7"}` |
| `dvm_object_graph` | all live refs grouped by class/scope | `{}` |
| `dvm_find_objects_by_class` | live handles of a class | `{"class":"com/example/mcpdemo/Vault"}` |
| `dvm_ref_table_stats` | ref-table counts + per-class histogram | `{}` |
| `dvm_pending_exception` | current pending JNI exception | `{}` |

**Native <-> DVM bridge** (a jobject/jmethodID peer is the same Int hash the DVM uses)

| Tool | Purpose | Example arguments |
|---|---|---|
| `dvm_resolve_native_handle` | native pointer/ID -> DVM object/method/field | `{"value":"0x...","kind":"auto"}` |
| `dvm_handle_to_native` | DVM handle -> native peer + scope/refCount | `{"hash":"0x..."}` |
| `dvm_class_of_native` | jclass peer or `Java_...` symbol -> DVM class | `{"symbol":"Java_com_example_mcpdemo_Vault_seal"}` |
| `dvm_args_at_breakpoint` | decode JNI args at a native breakpoint, mapped to DVM objects | `{"count":"4"}` |

**JNI hooks (native -> Java)**

| Tool | Purpose | Example arguments |
|---|---|---|
| `dvm_trace_jni` | install/refresh the interceptor, record callbacks | `{"enable":true}` |
| `dvm_jni_log` | read (and optionally clear) the trace + break log | `{"clear":"true"}` |
| `dvm_mock_jni` | override a callback's return (return-type aware) | `{"signature":"hex","return":"deadbeefdeadbeef"}` |
| `dvm_break_on_jni` | record a snapshot when a callback fires | `{"signature":"salt"}` |

**Snapshots, export, record/replay**

| Tool | Purpose | Example arguments |
|---|---|---|
| `dvm_snapshot` / `dvm_diff` | snapshot DVM state, then diff two snapshots | `{"name":"before"}` · `{"from":"before","to":"now"}` |
| `dvm_export` | write an object/snapshot/object_graph to disk | `{"what":"object","id":"0x...","path":"/tmp/x.json","format":"json"}` |
| `dvm_call_phase` | record a tool sequence and replay it | `{"action":"start","name":"p1"}` ... `{"action":"replay","name":"p1"}` |

---

## 6. Guided walkthrough (recommended order)

`run-all.sh` runs this exact order; `run-all.log` is the captured result. The high-value path:

1. **Orient:** `check_connection` → `list_modules` → `dvm_list_classes` → `dvm_dex_surface {kind:method}`
   to find the exact signature to call (it prints `class : name(args)ret`, paste-ready).
2. **Call:** `dvm_call_static Vault.seal(...)` returns the derived token. `dvm_oracle` asserts it,
   `dvm_fuzz_method` shows which output bytes depend on the input.
3. **Objects:** `dvm_new_object Vault("mylabel")` → handle; `dvm_call_instance ...transform("hello")`
   → `"OLLEH"`; `dvm_read_field label` → `"mylabel"`; `dvm_set_field counter=7` → read back `7`.
4. **Observe the bridge:** `dvm_trace_jni {enable:true}`, call seal, `dvm_jni_log` shows the
   `Device.salt()` / `Device.hex()` callbacks. `dvm_mock_jni hex` then seal again → the token changes.
   `dvm_break_on_jni salt` records a hit.
5. **Native debugging:** `add_breakpoint_by_symbol ...seal`, trigger seal, `poll_events` →
   `breakpoint_hit`, then `read_args` / `get_registers` / `disassemble` / `step_into` / `step_out` /
   `continue_execution`. `dvm_args_at_breakpoint` maps the arg registers to DVM objects.

---

## 7. Known constraints (honest notes)

* `dvm_to_string` usually falls back to a value preview: an app's `toString()` is a Java method, not
  native-registered, so the emulated path reports "not native-registered" and uses the host rendering.
* `read_std_string` is exercised by hand-crafting a libc++ SSO `std::string` blob in a scratch
  buffer (`06 6d 63 70 00` = "mcp") and reading it; on a real target you'd point it at an actual
  `std::string`.
* `dvm_new_object` / instance field access work by constructing/reading the **host** object under
  `ProxyClassFactory` (the normal app mode); `allocObject` is not supported by `ProxyJni`.
* `dvm_break_on_jni` records a snapshot at the callback; it does not suspend the thread (the
  emulate-on-call model has no separate thread to suspend) — use a native breakpoint to truly pause.

---

## 8. Files

```
src/com/example/mcpdemo/{Vault,Device}.java   the demo app (mixed Java + native)
jni/vault.c                                   the native lib source (seal + transform)
AndroidManifest.xml                           package com.example.mcpdemo
build.sh                                      builds out/mcpdemo.{apk,jar} + libvault.so
harness/McpDemoHarness.java                   boots Vortex + the MCP server for this app
run-all.sh                                    drives every MCP tool via curl (the tutorial)
run-all.log                                   captured output of a full run
out/                                          build artifacts (apk/jar; .gitignore'd intermediates)
```
