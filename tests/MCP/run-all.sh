#!/bin/bash
# =============================================================================
# Vortex-DBG MCP test suite — drive EVERY MCP tool against the McpDemo app.
#
# This script is both a test and a tutorial: it boots the McpDemoHarness, starts
# the MCP server, then acts as an MCP client (plain curl -> http://localhost:9239/sse)
# calling each tool in a sensible order, chaining object handles where needed.
# Every call prints an English header explaining what the tool does. Output is
# also saved to tests/MCP/run-all.log.
#
# Prereqs: tests/MCP/build.sh has produced out/mcpdemo.apk + out/mcpdemo.jar,
# and the project has been compiled (mvn compile). See tests/MCP/README.md.
# =============================================================================
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
JAVA=/Applications/JEB-Pro/bin/runtime/bin/java
JAVAC=/Applications/JEB-Pro/bin/runtime/bin/javac
LOG="tests/MCP/run-all.log"
PORT=9239
: > "$LOG"

# ---- classpath (module classes + test deps) ---------------------------------
MODCP="vortexdbg-api/target/classes:vortexdbg-android/target/classes:vortexdbg-android/target/test-classes"
for b in dynarmic unicorn2 kvm hypervisor; do MODCP="$MODCP:backend/$b/target/classes"; done
if [ ! -f /tmp/mcp_deps.txt ]; then
  echo ">>> resolving test dependency classpath (once)..."
  JAVA_HOME="$(dirname "$(dirname "$JAVA")")" ./mvnw -q -pl vortexdbg-android dependency:build-classpath \
    -DincludeScope=test -Dmdep.outputFile=/tmp/mcp_deps.txt >/dev/null 2>&1
fi
CP="$MODCP:$(cat /tmp/mcp_deps.txt)"

# ---- compile the harnesses if needed ----------------------------------------
echo ">>> compiling harnesses (01app + 02app + 03app + 04app)..."
"$JAVAC" -source 8 -target 8 -cp "$CP" -d vortexdbg-android/target/test-classes \
  tests/MCP/01app/harness/McpDemoHarness.java \
  tests/MCP/02app/harness/GuardHarness.java \
  tests/MCP/03app/harness/SecureHarness.java \
  tests/MCP/04app/harness/StoreHarness.java 2>/dev/null

# ---- MCP client helpers -----------------------------------------------------
# call <tool> <json-args> : POST a tools/call and print the text result.
call() {
  local body="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}"
  curl -s -X POST "http://localhost:$PORT/sse" -H 'Content-Type: application/json' -d "$body" \
    | sed -E 's/.*"text":"//; s/"}],"isError":true.*//; s/"}].*//' | sed 's/\\n/\n/g; s/\\"/"/g'
}
# extract first 0x-handle from a result, into global HANDLE
grab() { HANDLE=$(echo "$1" | grep -oE '0x[0-9a-f]+' | head -1); }
sec() { echo; echo "============================================================"; echo "## $1"; echo "============================================================"; }
t()   { echo; echo "--- $1 ---"; }

# boot <mainclass> : start a harness, type `mcp`, wait for the server. stop : kill it.
boot() {
  echo ">>> booting $1 ..."
  ( printf 'mcp\n'; sleep 90; printf 'exit\n'; sleep 1 ) | "$JAVA" -cp "$CP" "$1" > tests/MCP/harness.log 2>&1 &
  HPID=$!
  sleep 10
  grep -q "MCP server started" tests/MCP/harness.log || { echo "!! MCP server did not start for $1"; cat tests/MCP/harness.log; kill $HPID 2>/dev/null; exit 1; }
}
stop() { kill $HPID 2>/dev/null; wait 2>/dev/null; sleep 1; }

# ============================  PHASE 1: 01app  ===============================
boot com.vortexdbg.mcpdemo.McpDemoHarness

{
echo "################  VORTEX-DBG MCP TEST SUITE  ################"
echo "App: com.example.mcpdemo (Vault + Device), native: libvault.so (arm64)"

# =====================  NATIVE (ARM) TOOLS  =================================
sec "NATIVE (ARM) — status & modules"
t "check_connection — arch/backend/state/loaded modules";          call check_connection '{}'
t "list_modules (filter vault) — loaded .so modules";              call list_modules '{"filter":"vault"}'
t "get_module_info libvault.so — base/size/exports/deps";          call get_module_info '{"module_name":"libvault.so"}'
t "list_exports libvault.so — exported symbols";                   call list_exports '{"module_name":"libvault.so"}'
t "find_symbol libvault.so seal — resolve a symbol";              call find_symbol '{"module_name":"libvault.so","symbol_name":"Java_com_example_mcpdemo_Vault_seal"}'
t "get_threads — emulator threads/tasks";                          call get_threads '{}'

sec "NATIVE (ARM) — disassembly & memory (no breakpoint needed)"
t "disassemble_symbol libvault.so seal — disasm a function";       call disassemble_symbol '{"module_name":"libvault.so","symbol_name":"Java_com_example_mcpdemo_Vault_seal","count":"8"}'
t "assemble — text -> machine code";                               call assemble '{"assembly":"mov x0, #1"}'
t "list_memory_map — mapped regions";                              call list_memory_map '{}'
t "allocate_memory — RW scratch buffer";                           ALLOC=$(call allocate_memory '{"size":"16","data":"41424344"}'); echo "$ALLOC"; grab "$ALLOC"; AADDR=$HANDLE
t "read_memory @ allocated — hex dump";                            call read_memory "{\"address\":\"$AADDR\",\"size\":\"16\"}"
t "write_string @ allocated";                                      call write_string "{\"address\":\"$AADDR\",\"text\":\"hi-mcp\"}"
t "read_string @ allocated — C string";                            call read_string "{\"address\":\"$AADDR\"}"
t "read_typed @ allocated — typed view";                           call read_typed "{\"address\":\"$AADDR\",\"type\":\"int32\",\"count\":\"2\"}"
t "read_pointer @ allocated";                                      call read_pointer "{\"address\":\"$AADDR\"}"
t "search_memory — find 'hi-mcp' string (before we overwrite it)"; call search_memory '{"pattern":"hi-mcp","type":"string"}'
t "get_register X0 — read one register";                           call get_register '{"name":"X0"}'
t "set_register X0=0xdead — write one register";                   call set_register '{"name":"X0","value":"0xdead"}'
t "write_memory @ allocated (raw hex)";                            call write_memory "{\"address\":\"$AADDR\",\"hex_bytes\":\"01020304\"}"
t "patch @ allocated (assemble 'nop' + write)";                    call patch "{\"address\":\"$AADDR\",\"assembly\":\"nop\"}"
t "find_symbol libc.so strlen -> address";                         FS=$(call find_symbol '{"module_name":"libc.so","symbol_name":"strlen"}'); echo "$FS"; STRLEN=$(echo "$FS" | grep -oE '0x[0-9a-f]+' | head -1)
t "call_function @strlen('vortex') — call by address";             call call_function "{\"address\":\"${STRLEN:-0}\",\"args\":[\"s:vortex\"]}"
t "read_std_string — craft a libc++ SSO std::string ('mcp') and read it"; SS=$(call allocate_memory '{"size":"16","data":"066d637000"}'); grab "$SS"; SSADDR=$HANDLE; call read_std_string "{\"address\":\"$SSADDR\"}"; call free_memory "{\"address\":\"$SSADDR\"}" >/dev/null
t "list_allocations — tracked blocks";                             call list_allocations '{}'
t "free_memory @ allocated";                                       call free_memory "{\"address\":\"$AADDR\"}"
t "call_symbol libc.so strlen('mcp')";                             call call_symbol '{"module_name":"libc.so","symbol_name":"strlen","args":["s:mcp"]}'

# =====================  DALVIK / JAVA (DVM) TOOLS  =========================
sec "DVM — class introspection"
t "dvm_list_classes — resolved DVM classes";                       call dvm_list_classes '{}'
t "dvm_search_classes Vault";                                      call dvm_search_classes '{"query":"Vault"}'
t "dvm_class_hierarchy Vault";                                     call dvm_class_hierarchy '{"class":"com/example/mcpdemo/Vault"}'
t "dvm_describe_class Vault — members the VM has touched";         call dvm_describe_class '{"class":"com/example/mcpdemo/Vault"}'
t "dvm_describe_method Vault.seal";                                call dvm_describe_method '{"class":"com/example/mcpdemo/Vault","method":"seal(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"}'
t "dvm_resolve_method seal — find signatures by bare name";        call dvm_resolve_method '{"name":"seal"}'
t "dvm_list_native_registrations";                                 call dvm_list_native_registrations '{}'

sec "DVM — DEX static surface (reads the embedded classes.dex; no path needed)"
t "dvm_dex_surface classes";                                       call dvm_dex_surface '{"kind":"class"}'
t "dvm_dex_surface methods (filter seal)";                         call dvm_dex_surface '{"kind":"method","query":"seal"}'
t "dvm_dex_surface strings (filter mcpdemo)";                      call dvm_dex_surface '{"kind":"string","query":"mcpdemo"}'

sec "DVM — calling Java/native through the bridge"
t "dvm_call_static Vault.seal(alice,hunter2)";                     SEAL=$(call dvm_call_static '{"class":"com/example/mcpdemo/Vault","method":"seal(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;","args":["alice","hunter2"]}'); echo "$SEAL"
t "dvm_oracle — assert seal output is stable";                     EXP=$(echo "$SEAL" | grep -oE '[0-9a-f]{8,}' | head -1); call dvm_oracle "{\"class\":\"com/example/mcpdemo/Vault\",\"method\":\"seal(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;\",\"args\":[\"alice\",\"hunter2\"],\"expect\":\"$EXP\",\"match\":\"contains\"}"
t "dvm_fuzz_method seal — vary arg0 over 2 inputs";                call dvm_fuzz_method '{"class":"com/example/mcpdemo/Vault","method":"seal(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;","inputs":["alice","bob"],"arg_index":0,"fixed_args":["pw"]}'

sec "DVM — object lifecycle (create -> inspect -> use -> release)"
t "dvm_make_object string -> handle";                              MK=$(call dvm_make_object '{"type":"string","value":"hello-vortex"}'); echo "$MK"; grab "$MK"; HSTR=$HANDLE
t "dvm_read_string <handle>";                                      call dvm_read_string "{\"hash\":\"$HSTR\"}"
t "dvm_get_object <handle>";                                       call dvm_get_object "{\"hash\":\"$HSTR\"}"
t "dvm_to_string <handle>";                                        call dvm_to_string "{\"hash\":\"$HSTR\"}"
t "dvm_inspect_object <handle> — deep view + refcount";            call dvm_inspect_object "{\"hash\":\"$HSTR\"}"
t "dvm_make_object bytes -> handle";                               MB=$(call dvm_make_object '{"type":"bytes","value":"deadbeef"}'); echo "$MB"; grab "$MB"; HBYTES=$HANDLE
t "dvm_read_array <bytes handle>";                                 call dvm_read_array "{\"hash\":\"$HBYTES\"}"
t "dvm_new_object Vault(label) -> handle";                         NO=$(call dvm_new_object '{"class":"com/example/mcpdemo/Vault","method":"<init>(Ljava/lang/String;)V","args":["mylabel"]}'); echo "$NO"; grab "$NO"; HVAULT=$HANDLE
t "dvm_call_instance <vault>.transform('hello')";                  call dvm_call_instance "{\"hash\":\"$HVAULT\",\"method\":\"transform(Ljava/lang/String;)Ljava/lang/String;\",\"args\":[\"hello\"]}"
t "dvm_read_field <vault>.label (instance)";                       call dvm_read_field "{\"class\":\"com/example/mcpdemo/Vault\",\"field\":\"label\",\"type\":\"Ljava/lang/String;\",\"target_hash\":\"$HVAULT\"}"
t "dvm_set_field <vault>.counter = 7";                             call dvm_set_field "{\"target_hash\":\"$HVAULT\",\"field\":\"counter\",\"type\":\"I\",\"value\":\"7\"}"
t "dvm_read_field <vault>.counter (after set)";                    call dvm_read_field "{\"field\":\"counter\",\"type\":\"I\",\"target_hash\":\"$HVAULT\"}"
t "dvm_read_field Device.API_LEVEL (static)";                      call dvm_read_field '{"class":"com/example/mcpdemo/Device","field":"API_LEVEL","type":"I"}'
t "dvm_new_array_object [str handle, 'x']";                        call dvm_new_array_object "{\"elements\":[\"@$HSTR\",\"x\"]}"
t "dvm_pin_ref <str handle>";                                      call dvm_pin_ref "{\"hash\":\"$HSTR\"}"
t "dvm_release_ref <bytes handle>";                                call dvm_release_ref "{\"hash\":\"$HBYTES\"}"

sec "DVM — live object graph & refs"
t "dvm_list_objects";                                              LO=$(call dvm_list_objects '{}'); echo "$LO"; grab "$LO"; HOBJ=$HANDLE
t "dvm_object_graph";                                              call dvm_object_graph '{}'
t "dvm_find_objects_by_class Vault";                               call dvm_find_objects_by_class '{"class":"com/example/mcpdemo/Vault"}'
t "dvm_ref_table_stats";                                           call dvm_ref_table_stats '{}'
t "dvm_pending_exception";                                         call dvm_pending_exception '{}'

sec "DVM — native<->DVM bridge (peer == hash)"
t "dvm_resolve_native_handle <a live handle>";                     call dvm_resolve_native_handle "{\"value\":\"$HOBJ\",\"kind\":\"auto\"}"
t "dvm_handle_to_native <a live handle>";                          call dvm_handle_to_native "{\"hash\":\"$HOBJ\"}"
t "dvm_class_of_native (Java_ symbol)";                            call dvm_class_of_native '{"symbol":"Java_com_example_mcpdemo_Vault_seal"}'
t "dvm_args_at_breakpoint (current regs; no bp set)";              call dvm_args_at_breakpoint '{"count":"4"}'

sec "DVM — export, snapshot/diff"
t "dvm_snapshot before";                                           call dvm_snapshot '{"name":"before"}'
t "(trigger Vault.seal again to change the heap)";                 call dvm_call_static '{"class":"com/example/mcpdemo/Vault","method":"seal(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;","args":["carol","pw"]}' >/dev/null
t "dvm_diff before -> now";                                        call dvm_diff '{"from":"before","to":"now"}'
t "dvm_export bytes handle -> /tmp/mcp_export.bin";                call dvm_export "{\"what\":\"object\",\"id\":\"$HSTR\",\"path\":\"/tmp/mcp_export.json\",\"format\":\"json\"}"

sec "DVM — JNI hooks (native->Java): trace / mock / break"
t "dvm_trace_jni enable";                                          call dvm_trace_jni '{"enable":true}'
t "(trigger seal -> native calls back Device.salt()/hex())";       call dvm_call_static '{"class":"com/example/mcpdemo/Vault","method":"seal(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;","args":["dave","pw"]}' >/dev/null
t "dvm_jni_log — recorded callbacks";                              call dvm_jni_log '{}'
t "dvm_mock_jni hex -> deadbeefdeadbeef";                          call dvm_mock_jni '{"signature":"hex","return":"deadbeefdeadbeef"}'
t "(seal again, hex() now mocked)";                                call dvm_call_static '{"class":"com/example/mcpdemo/Vault","method":"seal(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;","args":["dave","pw"]}'
t "dvm_break_on_jni salt";                                         call dvm_break_on_jni '{"signature":"salt"}'
t "(seal again -> break event recorded)";                          call dvm_call_static '{"class":"com/example/mcpdemo/Vault","method":"seal(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;","args":["dave","pw"]}' >/dev/null
t "dvm_jni_log (clear=true) — see break events";                  call dvm_jni_log '{"clear":"true"}'
t "dvm_mock_jni hex remove; dvm_trace_jni disable";                call dvm_mock_jni '{"signature":"hex","remove":"true"}' >/dev/null; call dvm_trace_jni '{"enable":false}'
t "dvm_spoof_env preset=pixel — install device-identity mocks";    call dvm_spoof_env '{"preset":"pixel"}'
t "dvm_spoof_env disable — restore";                               call dvm_spoof_env '{"enable":false}'

sec "DVM — record / replay a tool sequence"
t "dvm_call_phase start p1";                                       call dvm_call_phase '{"action":"start","name":"p1"}'
t "(record: a seal + a class lookup)";                             call dvm_call_static '{"class":"com/example/mcpdemo/Vault","method":"seal(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;","args":["erin","pw"]}' >/dev/null; call dvm_class_hierarchy '{"class":"com/example/mcpdemo/Vault"}' >/dev/null
t "dvm_call_phase stop";                                           call dvm_call_phase '{"action":"stop"}'
t "dvm_call_phase list";                                           call dvm_call_phase '{"action":"list"}'
t "dvm_call_phase replay p1";                                      call dvm_call_phase '{"action":"replay","name":"p1"}'

# =====================  NATIVE BREAKPOINT FLOW  ===========================
sec "NATIVE (ARM) — breakpoint flow (break inside seal, inspect, step)"
t "add_breakpoint_by_symbol libvault.so seal";                     BP=$(call add_breakpoint_by_symbol '{"module_name":"libvault.so","symbol_name":"Java_com_example_mcpdemo_Vault_seal"}'); echo "$BP"; BPADDR=$(echo "$BP" | grep -oE '0x[0-9a-f]+' | head -1)
t "add_breakpoint_by_offset libvault.so +0x7e0 (transform)";       call add_breakpoint_by_offset '{"module_name":"libvault.so","offset":"0x7e0"}'
t "add_breakpoint (raw address) then remove — by-address bp";      call add_breakpoint '{"address":"0x12000570"}'; call remove_breakpoint '{"address":"0x12000570"}'
t "trace_code over seal prologue (events via poll_events)";        call trace_code "{\"begin\":\"$BPADDR\",\"end\":\"0x120005a0\"}"
t "trace_read over libvault range";                                call trace_read '{"begin":"0x12000000","end":"0x12002000"}'
t "trace_write over libvault range";                               call trace_write '{"begin":"0x12000000","end":"0x12002000"}'
t "list_breakpoints";                                              call list_breakpoints '{}'
t "(trigger seal via custom tool -> hits the breakpoint)";         curl -s -X POST "http://localhost:$PORT/sse" -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"seal","arguments":{"account":"frank","secret":"pw"}}}' >/dev/null; sleep 1
t "poll_events — expect breakpoint_hit";                           call poll_events '{"timeout_ms":"4000"}'
t "read_args — function args at entry";                            call read_args '{"count":"4"}'
t "get_registers — full register set";                             REGS=$(call get_registers '{}'); echo "$REGS"; PCV=$(echo "$REGS" | grep -oE 'PC=0x[0-9a-f]+' | sed 's/PC=//')
t "disassemble at PC ($PCV)";                                      call disassemble "{\"address\":\"${PCV:-0}\",\"count\":\"6\"}"
t "get_callstack — backtrace";                                     call get_callstack '{}'
t "step_into 2 instructions";                                      call step_into '{"count":"2"}'; call poll_events '{"timeout_ms":"3000"}'
t "step_over — step one instruction (over calls)";                 call step_over '{}'; call poll_events '{"timeout_ms":"3000"}'
t "next_block — run to next basic block";                          call next_block '{}'; call poll_events '{"timeout_ms":"3000"}'
t "step_until_mnemonic bl — run to next 'bl'";                     call step_until_mnemonic '{"mnemonic":"bl"}'; call poll_events '{"timeout_ms":"3000"}'
t "step_out — run until the function returns";                     call step_out '{}'; call poll_events '{"timeout_ms":"4000"}'
t "remove_breakpoint @bp ($BPADDR)";                               call remove_breakpoint "{\"address\":\"${BPADDR:-0}\"}"
t "continue_execution -> finish (+ flush trace_code events)";      call continue_execution '{}'; call poll_events '{"timeout_ms":"4000"}'

echo
echo "################  PHASE 1 (01app) DONE  ################"
} 2>&1 | tee -a "$LOG"
stop

# ============================  PHASE 2: 02app  ===============================
# 02app (com.example.guard) binds its natives via RegisterNatives and reads device-identity
# fields through JNI, so the tools that returned "empty but correct" on 01app now show a
# VISIBLE effect: RegisterNatives bindings appear, and dvm_spoof_env / dvm_mock_jni actually
# change what the native methods return.
boot com.vortexdbg.mcpguard.GuardHarness
GM='{"class":"com/example/guard/Guard","method":"deviceModel()Ljava/lang/String;","args":[]}'
GE='{"class":"com/example/guard/Guard","method":"isEmulator()Z","args":[]}'
{
echo "################  VORTEX-DBG MCP TEST SUITE — PHASE 2 (02app / Guard)  ################"
echo "RegisterNatives + JNI reads of Device.MODEL/FINGERPRINT -> visible spoof/mock effects."

sec "02app — RegisterNatives is now visible"
t "dvm_list_native_registrations — real bindings (was empty on 01app)"; call dvm_list_native_registrations '{}'
t "dvm_resolve_method deviceModel — now native-bound";             call dvm_resolve_method '{"name":"deviceModel"}'
t "dvm_describe_class Guard — native-bound section populated";      call dvm_describe_class '{"class":"com/example/guard/Guard"}'

sec "02app — dvm_spoof_env has a VISIBLE effect (defeat emulator detection)"
t "BASELINE deviceModel()";                                        call dvm_call_static "$GM"
t "BASELINE isEmulator() — detected as emulator";                  call dvm_call_static "$GE"
t "dvm_spoof_env preset=pixel";                                    call dvm_spoof_env '{"preset":"pixel"}'
t "deviceModel() AFTER spoof -> Pixel 7";                          call dvm_call_static "$GM"
t "isEmulator() AFTER spoof -> false (detection defeated)";        call dvm_call_static "$GE"
t "dvm_spoof_env disable";                                         call dvm_spoof_env '{"enable":false}'
t "deviceModel() after disable -> back to baseline";               call dvm_call_static "$GM"

sec "02app — dvm_mock_jni / dvm_trace_jni on the device reads"
t "dvm_trace_jni enable";                                          call dvm_trace_jni '{"enable":true}'
t "(call deviceModel -> native reads Device.MODEL via JNI)";       call dvm_call_static "$GM" >/dev/null
t "dvm_jni_log — recorded getStaticObjectField(MODEL)";            call dvm_jni_log '{}'
t "dvm_mock_jni MODEL -> PWNED-DEVICE";                            call dvm_mock_jni '{"signature":"MODEL","return":"PWNED-DEVICE"}'
t "deviceModel() with MODEL mocked";                               call dvm_call_static "$GM"
t "dvm_mock_jni MODEL remove; dvm_trace_jni disable";              call dvm_mock_jni '{"signature":"MODEL","remove":"true"}' >/dev/null; call dvm_trace_jni '{"enable":false}'

sec "02app — dvm_spoof_env currentTimeMillis affects bootToken()"
t "BASELINE bootToken() (System.currentTimeMillis()/1000)";        call dvm_call_static '{"class":"com/example/guard/Guard","method":"bootToken()J","args":[]}'
t "dvm_spoof_env currentTimeMillis=1700000000000";                 call dvm_spoof_env '{"overrides":{"currentTimeMillis":"1700000000000"}}'
t "bootToken() AFTER spoof -> 1700000000";                         call dvm_call_static '{"class":"com/example/guard/Guard","method":"bootToken()J","args":[]}'
t "dvm_spoof_env disable";                                         call dvm_spoof_env '{"enable":false}' >/dev/null; echo done

echo
echo "################  PHASE 2 (02app) DONE — see $LOG  ################"
} 2>&1 | tee -a "$LOG"
stop

# ============================  PHASE 3: 03app  ===============================
# 03app (com.example.secure) is a real C++ native target: libsecure.so uses std::string/std::vector
# and keeps the last plaintext in a libc++ std::string global, so read_std_string is exercised on an
# ACTUAL std::string (SSO + heap) rather than a hand-crafted blob.
boot com.vortexdbg.mcpsecure.SecureHarness
PROC='{"class":"com/example/secure/Secure","method":"process(Ljava/lang/String;)Ljava/lang/String;","args":'
{
echo "################  VORTEX-DBG MCP TEST SUITE — PHASE 3 (03app / Secure, real C++)  ################"
echo "libsecure.so is C++ (std::string/std::vector); read_std_string reads a REAL std::string global."

sec "03app — real C++ native crypto"
t "list_modules (libsecure.so)";                                   call list_modules '{"filter":"secure"}'
t "list_exports libsecure.so (filter secure)";                     call list_exports '{"module_name":"libsecure.so","filter":"secure"}'
t "disassemble_symbol Secure.process (real C++ codegen)";          call disassemble_symbol '{"module_name":"libsecure.so","symbol_name":"Java_com_example_secure_Secure_process","count":"8"}'
t "process('hi') -> hex ciphertext (rolling-key XOR)";             call dvm_call_static "$PROC[\"hi\"]}"

sec "03app — read_std_string on a REAL libc++ std::string (SSO)"
t "call_symbol secure_plaintext_addr -> &g_last_plaintext";        A=$(call call_symbol '{"module_name":"libsecure.so","symbol_name":"secure_plaintext_addr","args":[]}'); echo "$A"; ADDR=$(echo "$A" | grep -oE 'ret=0x[0-9a-f]+' | sed 's/ret=//')
t "read_std_string @ &g_last_plaintext -> 'hi' (SSO)";             call read_std_string "{\"address\":\"${ADDR:-0}\"}"

sec "03app — read_std_string on a heap std::string (long input)"
t "process(37-char license) to force heap allocation";             call dvm_call_static "$PROC[\"this-is-a-long-license-key-0123456789\"]}" >/dev/null
t "call_symbol secure_plaintext_addr (again)";                     A2=$(call call_symbol '{"module_name":"libsecure.so","symbol_name":"secure_plaintext_addr","args":[]}'); ADDR2=$(echo "$A2" | grep -oE 'ret=0x[0-9a-f]+' | sed 's/ret=//')
t "read_std_string @ heap std::string -> full plaintext";          call read_std_string "{\"address\":\"${ADDR2:-0}\"}"

echo
echo "################  PHASE 3 (03app) DONE — see $LOG  ################"
} 2>&1 | tee -a "$LOG"
stop

# ============================  PHASE 4: 04app  ===============================
# 04app (com.example.store) has a real C++ linked structure on the heap:
#   Session { u32 id; u32 flags; char name[16]; Session* next; }  (32 bytes)
#   g_head -> alice -> bob -> carol. This exercises read_pointer/read_typed/read_string/read_memory/
#   search_memory against ACTUAL structs, plus a multi-signal rootScore() (chain + access() su checks).
hexadd() { printf '0x%x' $(( $1 + $2 )); }   # hexadd 0x.. <dec offset>
boot com.vortexdbg.mcpstore.StoreHarness
{
echo "################  VORTEX-DBG MCP TEST SUITE — PHASE 4 (04app / Store, real C++ structs)  ################"
echo "Session = {u32 id; u32 flags; char name[16]; Session* next} (32B). g_head -> alice -> bob -> carol."

sec "04app — follow real pointers / read real struct fields"
t "Store.build() -> node count";                                   call dvm_call_static '{"class":"com/example/store/Store","method":"build()I","args":[]}'
t "call_symbol store_head_addr -> &g_head";                        HA=$(call call_symbol '{"module_name":"libstore.so","symbol_name":"store_head_addr","args":[]}'); echo "$HA"; HEAD=$(echo "$HA" | grep -oE 'ret=0x[0-9a-f]+' | sed 's/ret=//')
t "read_pointer @ &g_head -> first Session";                       RP=$(call read_pointer "{\"address\":\"${HEAD:-0}\"}"); echo "$RP"; NODEA=$(echo "$RP" | grep -oE '0x[0-9a-f]+' | tail -1)
t "read_typed @ node uint32 x2 -> id, flags (1, 1)";               call read_typed "{\"address\":\"${NODEA:-0}\",\"type\":\"uint32\",\"count\":\"2\"}"
t "read_string @ node+8 -> name ('alice')";                        call read_string "{\"address\":\"$(hexadd ${NODEA:-0} 8)\"}"
t "read_memory @ node (32-byte struct dump)";                      call read_memory "{\"address\":\"${NODEA:-0}\",\"size\":\"32\"}"
t "read_pointer @ node+24 -> next Session (->next)";               call read_pointer "{\"address\":\"$(hexadd ${NODEA:-0} 24)\"}"
t "search_memory 'carol' (the last node's name)";                  call search_memory '{"pattern":"carol","type":"string"}'

sec "04app — multi-signal rootScore (chain walk + access() su checks)"
t "Store.rootScore() -> deterministic risk score";                 call dvm_call_static '{"class":"com/example/store/Store","method":"rootScore()I","args":[]}'

echo
echo "################  PHASE 4 (04app) DONE — see $LOG  ################"
} 2>&1 | tee -a "$LOG"
stop
