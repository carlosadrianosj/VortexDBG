package com.github.unidbg.mcp;

import capstone.api.Instruction;
import capstone.api.RegsAccess;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.unidbg.Emulator;
import com.github.unidbg.Family;
import com.github.unidbg.Module;
import com.github.unidbg.Symbol;
import com.github.unidbg.TraceHook;
import com.github.unidbg.arm.ARM;
import com.github.unidbg.arm.Cpsr;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.debugger.BreakPoint;
import com.github.unidbg.debugger.Debugger;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.memory.MemoryMap;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.thread.Task;
import com.github.unidbg.unwind.Frame;
import com.github.unidbg.unwind.Unwinder;
import com.github.zhkl0228.demumble.DemanglerFactory;
import com.github.zhkl0228.demumble.GccDemangler;
import keystone.Keystone;
import keystone.KeystoneArchitecture;
import keystone.KeystoneEncoded;
import keystone.KeystoneMode;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import unicorn.Arm64Const;
import unicorn.ArmConst;
import unicorn.UnicornConst;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

public class McpTools {

    private final Emulator<?> emulator;
    private final McpServer server;
    private final List<CustomTool> customTools = new ArrayList<>();
    private final Map<Long, Allocation> allocatedBlocks = new LinkedHashMap<>();
    private TraceHook activeTraceCode;
    private TraceHook activeTraceRead;
    private TraceHook activeTraceWrite;

    private static class Allocation {
        final MemoryBlock block;
        final boolean runtime;
        final int size;
        Allocation(MemoryBlock block, boolean runtime, int size) {
            this.block = block;
            this.runtime = runtime;
            this.size = size;
        }
    }

    public McpTools(Emulator<?> emulator, McpServer server) {
        this.emulator = emulator;
        this.server = server;
    }

    public void addCustomTool(String name, String description, String... paramNames) {
        customTools.add(new CustomTool(name, description, paramNames));
    }

    public JSONArray getToolSchemas() {
        JSONArray tools = new JSONArray();
        tools.add(toolSchema("check_connection", "Check emulator status: architecture, backend, mode, state, modules. Call first."));
        tools.add(toolSchema("read_memory", "Read memory at address and return hex dump",
                param("address", "string", "Hex address, e.g. 0x40001000"),
                param("size", "integer", "Number of bytes to read, default 0x70")));
        tools.add(toolSchema("write_memory", "Write hex-encoded bytes to memory at address.",
                param("address", "string", "Hex address"),
                param("hex_bytes", "string", "Hex-encoded bytes, e.g. \"48656c6c6f\". Also accepts: data, hex_data, bytes.")));
        tools.add(toolSchema("list_memory_map", "List all memory mapped regions with base, size and permissions"));
        tools.add(toolSchema("search_memory", "Search memory for hex pattern (with ?? wildcards) or text string.",
                param("pattern", "string", "Hex bytes with optional ?? wildcards, or text if type='string'"),
                param("type", "string", "Optional. 'hex' (default) or 'string'"),
                param("module_name", "string", "Optional. Limit search to this module"),
                param("start", "string", "Optional. Hex start address"),
                param("end", "string", "Optional. Hex end address"),
                param("scope", "string", "Optional. 'stack' or 'heap'"),
                param("permission", "string", "Optional. For scope='heap': 'read'/'write'/'execute'. Default 'write'"),
                param("max_results", "integer", "Optional. Default 50")));

        tools.add(toolSchema("get_registers", "Read all general purpose registers"));
        tools.add(toolSchema("get_register", "Read a specific register by name",
                param("name", "string", "Register name, e.g. X0, R0, SP, PC, LR")));
        tools.add(toolSchema("set_register", "Write a value to a specific register",
                param("name", "string", "Register name"),
                param("value", "string", "Hex value to write")));

        tools.add(toolSchema("disassemble", "Disassemble instructions at address. Branch targets auto-annotated with symbol names.",
                param("address", "string", "Hex address"),
                param("count", "integer", "Number of instructions, default 10")));
        tools.add(toolSchema("assemble", "Assemble instruction text to machine code hex (does not write to memory)",
                param("assembly", "string", "Assembly instruction text, e.g. 'mov x0, #1'"),
                param("address", "string", "Hex address for PC-relative encoding, default 0")));
        tools.add(toolSchema("patch", "Assemble instruction and write to memory at address",
                param("address", "string", "Hex address to patch"),
                param("assembly", "string", "Assembly instruction text")));
        tools.add(toolSchema("add_breakpoint", "Add breakpoint at address.",
                param("address", "string", "Hex address"),
                param("temporary", "boolean", "Auto-remove after first hit. Default false.")));
        tools.add(toolSchema("remove_breakpoint", "Remove breakpoint at address.",
                param("address", "string", "Hex address")));
        tools.add(toolSchema("list_breakpoints", "List all breakpoints with address, module info and disassembly."));
        tools.add(toolSchema("add_breakpoint_by_symbol", "Add breakpoint at module symbol (preferred over find_symbol + add_breakpoint).",
                param("module_name", "string", "Module name"),
                param("symbol_name", "string", "Symbol name, e.g. JNI_OnLoad"),
                param("temporary", "boolean", "Auto-remove after first hit. Default false.")));
        tools.add(toolSchema("add_breakpoint_by_offset", "Add breakpoint at module base + offset (for IDA/Ghidra offsets).",
                param("module_name", "string", "Module name"),
                param("offset", "string", "Hex offset, e.g. 0x1234"),
                param("temporary", "boolean", "Auto-remove after first hit. Default false.")));
        tools.add(toolSchema("continue_execution", "Resume execution from current PC."));
        tools.add(toolSchema("step_over", "Step over current instruction (skip function calls)."));
        tools.add(toolSchema("step_into", "Execute N instructions then stop.",
                param("count", "integer", "Number of instructions. Default 1.")));
        tools.add(toolSchema("step_out", "Run until current function returns (bp at LR)."));
        tools.add(toolSchema("next_block", "Break at next basic block start. Unicorn only."));
        tools.add(toolSchema("step_until_mnemonic", "Break when specified mnemonic executes. Unicorn only.",
                param("mnemonic", "string", "e.g. 'bl', 'ret'. Lowercase.")));
        tools.add(toolSchema("poll_events", "Poll runtime events (breakpoint_hit, execution_completed, trace_code/read/write). Call after execution tools.",
                param("timeout_ms", "integer", "Max ms to wait. Default 10000. 0=no wait.")));

        tools.add(toolSchema("trace_read", "Trace memory reads in address range. Events via poll_events. Auto-removed on bp/step/finish.",
                param("begin", "string", "Hex start address"),
                param("end", "string", "Hex end address"),
                param("break_on", "string", "Optional. Hex address to pause on when hit.")));
        tools.add(toolSchema("trace_write", "Trace memory writes in address range. Events via poll_events. Auto-removed on bp/step/finish.",
                param("begin", "string", "Hex start address"),
                param("end", "string", "Hex end address"),
                param("break_on", "string", "Optional. Hex address to pause on when hit.")));
        tools.add(toolSchema("trace_code", "Trace instruction execution in range. Events include regs_read (before) and prev_write (after previous insn) for data flow tracking.",
                param("begin", "string", "Hex start address"),
                param("end", "string", "Hex end address"),
                param("break_on", "string", "Optional. Hex PC to pause on when reached.")));
        tools.add(toolSchema("get_callstack", "Get call stack (backtrace) with PC, module, offset and nearest symbol."));
        tools.add(toolSchema("find_symbol", "Find symbol by name in module, or find nearest symbol to an address.",
                param("module_name", "string", "Optional. Module name"),
                param("symbol_name", "string", "Optional. Symbol name"),
                param("address", "string", "Optional. Hex address to find nearest symbol for")));
        tools.add(toolSchema("read_string", "Read null-terminated C string (UTF-8) from memory.",
                param("address", "string", "Hex address"),
                param("max_length", "integer", "Max bytes. Default 256.")));
        tools.add(toolSchema("read_std_string", "Read C++ std::string (libc++ layout, auto-detects SSO/heap).",
                param("address", "string", "Hex address of std::string object")));
        tools.add(toolSchema("read_pointer", "Read pointer(s) at address, optionally follow chain (isa, vtable, etc).",
                param("address", "string", "Hex address"),
                param("depth", "integer", "Optional. Chain depth. Default 1."),
                param("offset", "integer", "Optional. Byte offset at each dereference. Default 0.")));
        tools.add(toolSchema("read_typed", "Read memory as typed values: int8/16/32/64, uint8/16/32/64, float, double, pointer.",
                param("address", "string", "Hex address"),
                param("type", "string", "Data type"),
                param("count", "integer", "Optional. Number of elements. Default 1.")));
        tools.add(toolSchema("call_function", "Call native function at address. Requires isRunning=false. Arg format in instructions. " +
                        "Traces set before this call remain active. Returns function result with auto pointer preview.",
                param("address", "string", "Hex address of function"),
                argsParam(),
                param("preview_size", "integer", "Optional. Hex preview bytes at return pointer. Default 64. 0=disable.")));

        tools.add(toolSchema("call_symbol", "Call exported function by module+symbol name. Requires isRunning=false. Same arg format as call_function.",
                param("module_name", "string", "Module name, e.g. 'libc.so'"),
                param("symbol_name", "string", "Symbol name, e.g. 'malloc'"),
                argsParam(),
                param("preview_size", "integer", "Optional. Default 64. 0=disable.")));

        tools.add(toolSchema("list_modules", "List loaded modules with name, base and size.",
                param("filter", "string", "Optional. Case-insensitive name filter.")));
        tools.add(toolSchema("get_module_info", "Get module details: base, size, exports count, dependencies.",
                param("module_name", "string", "Module name")));
        tools.add(toolSchema("list_exports", "List exported symbols of a module with addresses and demangled names.",
                param("module_name", "string", "Module name"),
                param("filter", "string", "Optional. Case-insensitive name filter.")));
        tools.add(toolSchema("get_threads", "List all threads/tasks with IDs and status."));
        tools.add(toolSchema("allocate_memory", "Allocate RW memory. See instructions for malloc vs mmap strategy.",
                param("size", "integer", "Bytes to allocate (or inferred from data)"),
                param("data", "string", "Optional. Hex bytes to fill immediately."),
                param("runtime", "boolean", "Optional. true=mmap, false=malloc. Auto-selected based on state.")));
        tools.add(toolSchema("free_memory", "Free memory allocated via allocate_memory.",
                param("address", "string", "Hex address to free")));
        tools.add(toolSchema("list_allocations", "List active allocations from allocate_memory."));

        if (emulator.getFamily() == Family.iOS) {
            tools.add(toolSchema("inspect_objc_msg", "Show current objc_msgSend: receiver class + selector from X0/X1. Pure memory parsing, no state change."));
            tools.add(toolSchema("get_objc_class_name", "Get ObjC class name of object. Pure memory parsing, no state change.",
                    param("address", "string", "Hex address of ObjC object")));
            tools.add(toolSchema("dump_objc_class", "Dump ObjC class definition (properties, methods, ivars). Requires isRunning=false. Modifies registers/stack.",
                    param("class_name", "string", "Exact ObjC class name, e.g. 'NSString'")));
        }

        if (emulator.getFamily() == Family.iOS && emulator.is64Bit()) {
            tools.add(toolSchema("dump_gpb_protobuf", "Dump GPB protobuf message schema as .proto. iOS 64-bit only. Requires isRunning=false. Modifies registers/stack.",
                    param("class_name", "string", "GPBMessage subclass name, e.g. 'GPBStruct', 'MyApp_SearchRequest'")));
        }

        for (CustomTool ct : customTools) {
            JSONObject schema = new JSONObject(true);
            schema.put("name", ct.name);
            schema.put("description", "[Custom] " + ct.description + ". Triggers target function execution (library already loaded). Set breakpoints/traces BEFORE calling, then poll_events for results.");
            schema.put("inputSchema", buildInputSchema(ct.paramNames));
            tools.add(schema);
        }
        return tools;
    }

    public JSONObject callTool(String name, JSONObject args) {
        if (isExecutionTool(name)) {
            return dispatchTool(name, args);
        }
        if (!server.isDebugIdle()) {
            return errorResult("Emulator is not in debug idle state. Tools can only be called when emulator is stopped at a breakpoint.");
        }
        return server.runOnDebuggerThread(() -> dispatchTool(name, args));
    }

    private boolean isExecutionTool(String name) {
        if ("continue_execution".equals(name)) return true;
        if ("step_over".equals(name)) return true;
        if ("step_into".equals(name)) return true;
        if ("step_out".equals(name)) return true;
        if ("next_block".equals(name)) return true;
        if ("step_until_mnemonic".equals(name)) return true;
        if ("poll_events".equals(name)) return true;
        if ("check_connection".equals(name)) return true;
        for (CustomTool ct : customTools) {
            if (ct.name.equals(name)) return true;
        }
        return false;
    }

    private JSONObject dispatchTool(String name, JSONObject args) {
        switch (name) {
            case "check_connection": return checkConnection();
            case "read_memory": return readMemory(args);
            case "write_memory": return writeMemory(args);
            case "list_memory_map": return listMemoryMap();
            case "search_memory": return searchMemory(args);
            case "get_registers": return getRegisters();
            case "get_register": return getRegister(args);
            case "set_register": return setRegister(args);
            case "disassemble": return disassemble(args);
            case "assemble": return assemble(args);
            case "patch": return patch(args);
            case "add_breakpoint": return addBreakpoint(args);
            case "add_breakpoint_by_symbol": return addBreakpointBySymbol(args);
            case "add_breakpoint_by_offset": return addBreakpointByOffset(args);
            case "remove_breakpoint": return removeBreakpoint(args);
            case "list_breakpoints": return listBreakpoints();
            case "continue_execution": return continueExecution();
            case "step_over": return stepOver();
            case "step_into": return stepInto(args);
            case "step_out": return stepOut();
            case "next_block": return nextBlock();
            case "step_until_mnemonic": return stepUntilMnemonic(args);
            case "poll_events": return pollEvents(args);
            case "trace_read": return traceRead(args);
            case "trace_write": return traceWrite(args);
            case "trace_code": return traceCode(args);
            case "get_callstack": return getCallstack();
            case "find_symbol": return findSymbol(args);
            case "read_string": return readString(args);
            case "read_std_string": return readStdString(args);
            case "read_pointer": return readPointer(args);
            case "read_typed": return readTyped(args);
            case "call_function": return callFunction(args);
            case "call_symbol": return callSymbol(args);
            case "list_modules": return listModules(args);
            case "get_module_info": return getModuleInfo(args);
            case "list_exports": return listExports(args);
            case "get_threads": return getThreads();
            case "allocate_memory": return allocateMemory(args);
            case "free_memory": return freeMemory(args);
            case "list_allocations": return listAllocations();
            case "inspect_objc_msg": return inspectObjcMsg();
            case "get_objc_class_name": return getObjcClassName(args);
            case "dump_objc_class": return dumpObjcClass(args);
            case "dump_gpb_protobuf": return dumpGpbProtobuf(args);
            default:
                for (CustomTool ct : customTools) {
                    if (ct.name.equals(name)) {
                        return executeCustomTool(ct, args);
                    }
                }
                return errorResult("Unknown tool: " + name);
        }
    }

    private JSONObject checkConnection() {
        StringBuilder sb = new StringBuilder();
        Family family = emulator.getFamily();
        String backendClass = emulator.getBackend().getClass().getSimpleName();
        Debugger debugger = emulator.attach();
        boolean hasRunnable = debugger.hasRunnable();
        sb.append(family.name()).append(' ').append(emulator.is64Bit() ? "ARM64" : "ARM32")
                .append(" | ").append(backendClass).append(" (").append(getBackendCapabilities(backendClass)).append(")\n");
        String processName = emulator.getProcessName();
        int lastSlash = processName.lastIndexOf('/');
        sb.append(lastSlash >= 0 ? processName.substring(lastSlash + 1) : processName);
        sb.append(" | ").append(hasRunnable ? "custom_tools" : "bp_debug")
                .append(" idle=").append(server.isDebugIdle())
                .append(" running=").append(emulator.isRunning())
                .append(" bp=").append(debugger.getBreakPoints().size())
                .append(" events=").append(server.getPendingEventCount()).append('\n');
        Collection<Module> modules = emulator.getMemory().getLoadedModules();
        sb.append(modules.size()).append(" modules");
        for (Module m : modules) {
            boolean isSystemLib = m.name.startsWith("lib") || m.base > 0x100000000L;
            if (!isSystemLib) {
                sb.append(", ").append(m.name).append("@0x").append(Long.toHexString(m.base));
            }
        }
        sb.append('\n');
        return textResult(sb.toString());
    }

    private static String getBackendCapabilities(String backendClass) {
        if (backendClass.contains("Unicorn")) return "FULL";
        if (backendClass.contains("Hypervisor")) return "PARTIAL";
        if (backendClass.contains("Dynarmic") || backendClass.contains("Kvm")) return "MINIMAL";
        return "unknown";
    }

    private JSONObject readMemory(JSONObject args) {
        long address = parseAddress(args.getString("address"));
        int size = args.containsKey("size") ? args.getIntValue("size") : 0x70;
        try {
            byte[] data = emulator.getBackend().mem_read(address, size);
            return textResult(hexDump(data, address));
        } catch (Exception e) {
            return errorResult("Failed to read memory at 0x" + Long.toHexString(address) + ": " + exMsg(e));
        }
    }

    private static String hexDump(byte[] data, long baseAddr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 16) {
            sb.append(String.format("%08x: ", baseAddr + i));
            int end = Math.min(i + 16, data.length);
            for (int j = i; j < i + 16; j++) {
                if (j < end) {
                    sb.append(String.format("%02X ", data[j]));
                } else {
                    sb.append("   ");
                }
            }
            sb.append(' ');
            for (int j = i; j < end; j++) {
                char c = (char) (data[j] & 0xFF);
                sb.append(c >= 0x20 && c <= 0x7e ? c : '.');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private JSONObject writeMemory(JSONObject args) {
        long address = parseAddress(args.getString("address"));
        String hexBytes = args.getString("hex_bytes");
        if (hexBytes == null) hexBytes = args.getString("data");
        if (hexBytes == null) hexBytes = args.getString("hex_data");
        if (hexBytes == null) hexBytes = args.getString("bytes");
        if (hexBytes == null) {
            return errorResult("Missing required parameter. Use 'hex_bytes' (hex encoded string, e.g. \"48656c6c6f\"). " +
                    "Also accepts aliases: 'data', 'hex_data', 'bytes'.");
        }
        try {
            byte[] data = Hex.decodeHex(hexBytes.toCharArray());
            emulator.getBackend().mem_write(address, data);
            return textResult("Written " + data.length + " bytes to 0x" + Long.toHexString(address));
        } catch (DecoderException e) {
            return errorResult("Invalid hex string: \"" + hexBytes + "\". Expected hex-encoded bytes, e.g. \"48656c6c6f\" for \"Hello\".");
        } catch (Exception e) {
            return errorResult("Failed to write memory at 0x" + Long.toHexString(address) + ": " + exMsg(e));
        }
    }

    private JSONObject listMemoryMap() {
        Collection<MemoryMap> maps = emulator.getMemory().getMemoryMap();
        Memory memory = emulator.getMemory();
        LinkedHashMap<String, long[]> moduleRanges = new LinkedHashMap<>();
        List<long[]> anonymous = new ArrayList<>();
        for (MemoryMap map : maps) {
            Module module = memory.findModuleByAddress(map.base);
            if (module != null) {
                long[] range = moduleRanges.get(module.name);
                long end = map.base + map.size;
                if (range == null) {
                    moduleRanges.put(module.name, new long[]{map.base, end});
                } else {
                    if (map.base < range[0]) range[0] = map.base;
                    if (end > range[1]) range[1] = end;
                }
            } else {
                anonymous.add(new long[]{map.base, map.base + map.size, map.prot});
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(moduleRanges.size()).append(" modules, ").append(anonymous.size()).append(" anonymous:\n");
        for (Map.Entry<String, long[]> e : moduleRanges.entrySet()) {
            long[] r = e.getValue();
            sb.append(e.getKey()).append(" 0x").append(Long.toHexString(r[0]))
                    .append("-0x").append(Long.toHexString(r[1]))
                    .append(" 0x").append(Long.toHexString(r[1] - r[0])).append('\n');
        }
        for (long[] r : anonymous) {
            sb.append(String.format("0x%x-0x%x 0x%x %s\n", r[0], r[1], r[1] - r[0], permString((int) r[2])));
        }
        return textResult(sb.toString());
    }

    private JSONObject searchMemory(JSONObject args) {
        String patternStr = args.getString("pattern");
        String type = args.containsKey("type") ? args.getString("type") : "hex";
        String moduleName = args.getString("module_name");
        String startStr = args.getString("start");
        String endStr = args.getString("end");
        String scope = args.getString("scope");
        String permission = args.getString("permission");
        int maxResults = args.containsKey("max_results") ? args.getIntValue("max_results") : 50;

        byte[] patternBytes;
        byte[] maskBytes;
        try {
            if ("string".equalsIgnoreCase(type)) {
                patternBytes = patternStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                maskBytes = null;
            } else {
                String hex = patternStr.replace(" ", "");
                if (hex.length() % 2 != 0) {
                    return errorResult("Hex pattern must have even number of characters: " + patternStr);
                }
                int byteLen = hex.length() / 2;
                patternBytes = new byte[byteLen];
                maskBytes = new byte[byteLen];
                boolean hasMask = false;
                for (int i = 0; i < byteLen; i++) {
                    String byteStr = hex.substring(i * 2, i * 2 + 2);
                    if ("??".equals(byteStr)) {
                        patternBytes[i] = 0;
                        maskBytes[i] = 0;
                        hasMask = true;
                    } else {
                        patternBytes[i] = (byte) Integer.parseInt(byteStr, 16);
                        maskBytes[i] = (byte) 0xFF;
                    }
                }
                if (!hasMask) {
                    maskBytes = null;
                }
            }
        } catch (NumberFormatException e) {
            return errorResult("Invalid hex pattern: " + patternStr);
        }

        List<long[]> ranges = new ArrayList<>();
        if ("stack".equalsIgnoreCase(scope)) {
            UnidbgPointer sp = emulator.getContext().getStackPointer();
            long stackBase = emulator.getMemory().getStackBase();
            ranges.add(new long[]{sp.peer, stackBase});
        } else if ("heap".equalsIgnoreCase(scope)) {
            int prot = resolvePermission(permission);
            for (MemoryMap map : emulator.getMemory().getMemoryMap()) {
                if ((map.prot & prot) != 0) {
                    ranges.add(new long[]{map.base, map.base + map.size});
                }
            }
        } else if (moduleName != null && !moduleName.isEmpty()) {
            Module module = emulator.getMemory().findModule(moduleName);
            if (module == null) {
                return errorResult("Module not found: " + moduleName);
            }
            ranges.add(new long[]{module.base, module.base + module.size});
        } else if (startStr != null && endStr != null) {
            ranges.add(new long[]{parseAddress(startStr), parseAddress(endStr)});
        } else {
            for (MemoryMap map : emulator.getMemory().getMemoryMap()) {
                if ((map.prot & 1) != 0) {
                    ranges.add(new long[]{map.base, map.base + map.size});
                }
            }
        }

        Backend backend = emulator.getBackend();
        Memory memory = emulator.getMemory();
        List<String> results = new ArrayList<>();
        int chunkSize = 0x10000;

        for (long[] range : ranges) {
            long rangeStart = range[0];
            long rangeEnd = range[1];
            long overlap = patternBytes.length - 1;
            long step = Math.max(1, chunkSize - overlap);

            for (long addr = rangeStart; addr < rangeEnd && results.size() < maxResults; addr += step) {
                int readSize = (int) Math.min(chunkSize, rangeEnd - addr);
                byte[] chunk;
                try {
                    chunk = backend.mem_read(addr, readSize);
                } catch (Exception e) {
                    continue;
                }
                for (int i = 0; i <= chunk.length - patternBytes.length && results.size() < maxResults; i++) {
                    if (matchPattern(chunk, i, patternBytes, maskBytes)) {
                        long matchAddr = addr + i;
                        StringBuilder sb = new StringBuilder();
                        sb.append("0x").append(Long.toHexString(matchAddr));
                        Module module = memory.findModuleByAddress(matchAddr);
                        if (module != null) {
                            sb.append("  (").append(module.name).append("+0x").append(Long.toHexString(matchAddr - module.base)).append(')');
                        }
                        results.add(sb.toString());
                    }
                }
            }
            if (results.size() >= maxResults) break;
        }

        if (results.isEmpty()) {
            return textResult("Pattern not found.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" match(es)");
        if (results.size() >= maxResults) {
            sb.append(" (limit reached)");
        }
        sb.append(":\n");
        for (String r : results) {
            sb.append(r).append('\n');
        }
        return textResult(sb.toString());
    }

    private static boolean matchPattern(byte[] data, int offset, byte[] pattern, byte[] mask) {
        for (int j = 0; j < pattern.length; j++) {
            if (mask != null) {
                if ((data[offset + j] & mask[j]) != (pattern[j] & mask[j])) {
                    return false;
                }
            } else {
                if (data[offset + j] != pattern[j]) {
                    return false;
                }
            }
        }
        return true;
    }

    private JSONObject getRegisters() {
        Backend backend = emulator.getBackend();
        StringBuilder sb = new StringBuilder();
        if (emulator.is64Bit()) {
            List<String> zeros = new ArrayList<>();
            for (int i = 0; i <= 28; i++) {
                long val = backend.reg_read(Arm64Const.UC_ARM64_REG_X0 + i).longValue();
                if (val == 0) {
                    zeros.add("X" + i);
                } else {
                    sb.append("X").append(i).append("=0x").append(Long.toHexString(val)).append('\n');
                }
            }
            sb.append("FP=0x").append(Long.toHexString(backend.reg_read(Arm64Const.UC_ARM64_REG_FP).longValue())).append('\n');
            sb.append("LR=0x").append(Long.toHexString(backend.reg_read(Arm64Const.UC_ARM64_REG_LR).longValue())).append('\n');
            sb.append("SP=0x").append(Long.toHexString(backend.reg_read(Arm64Const.UC_ARM64_REG_SP).longValue())).append('\n');
            sb.append("PC=0x").append(Long.toHexString(backend.reg_read(Arm64Const.UC_ARM64_REG_PC).longValue())).append('\n');
            if (!zeros.isEmpty()) {
                sb.append("Zero: ").append(String.join(",", zeros)).append('\n');
            }
        } else {
            List<String> zeros = new ArrayList<>();
            for (int i = 0; i <= 12; i++) {
                long val = backend.reg_read(ArmConst.UC_ARM_REG_R0 + i).intValue() & 0xffffffffL;
                if (val == 0) {
                    zeros.add("R" + i);
                } else {
                    sb.append("R").append(i).append("=0x").append(Long.toHexString(val)).append('\n');
                }
            }
            sb.append("SP=0x").append(Long.toHexString(backend.reg_read(ArmConst.UC_ARM_REG_SP).intValue() & 0xffffffffL)).append('\n');
            sb.append("LR=0x").append(Long.toHexString(backend.reg_read(ArmConst.UC_ARM_REG_LR).intValue() & 0xffffffffL)).append('\n');
            sb.append("PC=0x").append(Long.toHexString(backend.reg_read(ArmConst.UC_ARM_REG_PC).intValue() & 0xffffffffL)).append('\n');
            if (!zeros.isEmpty()) {
                sb.append("Zero: ").append(String.join(",", zeros)).append('\n');
            }
        }
        return textResult(sb.toString());
    }

    private JSONObject getRegister(JSONObject args) {
        String raw = args.getString("name");
        if (raw == null || raw.isEmpty()) {
            return errorResult("Missing required parameter 'name'. Specify a register name, e.g. X0, SP, PC.");
        }
        String name = raw.toUpperCase();
        try {
            int regId = resolveRegister(name);
            Backend backend = emulator.getBackend();
            if (emulator.is64Bit()) {
                long val = backend.reg_read(regId).longValue();
                if (name.startsWith("W")) {
                    val &= 0xFFFFFFFFL;
                }
                return textResult(name + " = 0x" + Long.toHexString(val));
            } else {
                long val = backend.reg_read(regId).intValue() & 0xffffffffL;
                return textResult(name + " = 0x" + Long.toHexString(val));
            }
        } catch (Exception e) {
            return errorResult("Failed to read register " + name + ": " + exMsg(e));
        }
    }

    private JSONObject setRegister(JSONObject args) {
        String raw = args.getString("name");
        if (raw == null || raw.isEmpty()) {
            return errorResult("Missing required parameter 'name'. Specify a register name, e.g. X0, SP, PC.");
        }
        String name = raw.toUpperCase();
        long value = parseAddress(args.getString("value"));
        try {
            int regId = resolveRegister(name);
            emulator.getBackend().reg_write(regId, value);
            return textResult(name + " set to 0x" + Long.toHexString(value));
        } catch (Exception e) {
            return errorResult("Failed to set register " + name + ": " + exMsg(e));
        }
    }

    private JSONObject disassemble(JSONObject args) {
        long address = parseAddress(args.getString("address"));
        int count = args.containsKey("count") ? args.getIntValue("count") : 10;
        try {
            int size = count * 4;
            byte[] code = emulator.getBackend().mem_read(address, size);
            boolean thumb = emulator.is32Bit() && ARM.isThumb(emulator.getBackend());
            Instruction[] insns = emulator.disassemble(address, code, thumb, count);
            Memory memory = emulator.getMemory();
            GccDemangler demangler = DemanglerFactory.createDemangler();
            StringBuilder sb = new StringBuilder();
            for (Instruction insn : insns) {
                sb.append(String.format("0x%x: %s %s", insn.getAddress(), insn.getMnemonic(), insn.getOpStr()));
                String annotation = resolveInsnTargetSymbol(insn, memory, demangler);
                if (annotation != null) {
                    sb.append("  ; ").append(annotation);
                }
                sb.append('\n');
            }
            if (insns.length == 0) {
                sb.append("No instructions at 0x").append(Long.toHexString(address));
            }
            return textResult(sb.toString());
        } catch (Exception e) {
            return errorResult("Disassemble failed: " + exMsg(e));
        }
    }

    private static final java.util.regex.Pattern IMM_ADDR_PATTERN = java.util.regex.Pattern.compile("#0x([0-9a-fA-F]+)");

    private String resolveInsnTargetSymbol(Instruction insn, Memory memory, GccDemangler demangler) {
        String mnemonic = insn.getMnemonic().toLowerCase();
        if (!isBranchMnemonic(mnemonic)) {
            return null;
        }
        java.util.regex.Matcher m = IMM_ADDR_PATTERN.matcher(insn.getOpStr());
        long target = -1;
        while (m.find()) {
            try {
                target = Long.parseUnsignedLong(m.group(1), 16);
            } catch (NumberFormatException ignored) {
            }
        }
        if (target <= 0) {
            return null;
        }
        Module module = memory.findModuleByAddress(target);
        if (module == null) {
            return null;
        }
        Symbol symbol = module.findClosestSymbolByAddress(target, false);
        if (symbol != null && target - symbol.getAddress() <= Unwinder.SYMBOL_SIZE) {
            String name = demangler.demangle(symbol.getName());
            long offset = target - symbol.getAddress();
            if (offset == 0) {
                return name;
            }
            return name + "+0x" + Long.toHexString(offset);
        }
        return module.name + "+0x" + Long.toHexString(target - module.base);
    }

    private static boolean isBranchMnemonic(String mnemonic) {
        switch (mnemonic) {
            case "b": case "bl": case "br": case "blr":
            case "cbz": case "cbnz": case "tbz": case "tbnz":
            case "bx": case "blx":
                return true;
            default:
                if (mnemonic.startsWith("b.")) return true;
                if (mnemonic.startsWith("bl") && mnemonic.length() <= 5) return true;
                return mnemonic.startsWith("b") && mnemonic.length() <= 4
                        && !mnemonic.startsWith("bic") && !mnemonic.startsWith("bfi") && !mnemonic.startsWith("bfc");
        }
    }

    private JSONObject assemble(JSONObject args) {
        String assembly = args.getString("assembly");
        try (Keystone keystone = createKeystone()) {
            KeystoneEncoded encoded = keystone.assemble(assembly);
            byte[] code = encoded.getMachineCode();
            return textResult("Machine code: " + Hex.encodeHexString(code) + " (" + code.length + " bytes)");
        } catch (Exception e) {
            return errorResult("Assemble failed: " + exMsg(e));
        }
    }

    private JSONObject patch(JSONObject args) {
        long address = parseAddress(args.getString("address"));
        String assembly = args.getString("assembly");
        try (Keystone keystone = createKeystone()) {
            KeystoneEncoded encoded = keystone.assemble(assembly);
            byte[] code = encoded.getMachineCode();
            emulator.getBackend().mem_write(address, code);
            return textResult("Patched " + code.length + " bytes at 0x" + Long.toHexString(address) +
                    ": " + Hex.encodeHexString(code));
        } catch (Exception e) {
            return errorResult("Patch failed: " + exMsg(e));
        }
    }

    private JSONObject addBreakpoint(JSONObject args) {
        long address = parseAddress(args.getString("address"));
        boolean temporary = args.containsKey("temporary") && args.getBooleanValue("temporary");
        try {
            BreakPoint bp = emulator.attach().addBreakPoint(address);
            if (temporary) {
                bp.setTemporary(true);
            }
            String type = temporary ? "Temporary breakpoint" : "Breakpoint";
            return textResult(type + " added at 0x" + Long.toHexString(address));
        } catch (Exception e) {
            return errorResult("Failed to add breakpoint: " + exMsg(e));
        }
    }

    private JSONObject addBreakpointBySymbol(JSONObject args) {
        String moduleName = args.getString("module_name");
        String symbolName = args.getString("symbol_name");
        boolean temporary = args.containsKey("temporary") && args.getBooleanValue("temporary");
        try {
            Module module = emulator.getMemory().findModule(moduleName);
            if (module == null) {
                return errorResult("Module not found: " + moduleName);
            }
            Debugger debugger = emulator.attach();
            BreakPoint bp = debugger.addBreakPoint(module, symbolName);
            if (bp == null) {
                return errorResult("Symbol '" + symbolName + "' not found in " + moduleName);
            }
            if (temporary) {
                bp.setTemporary(true);
            }
            long addr = 0;
            for (Map.Entry<Long, BreakPoint> entry : debugger.getBreakPoints().entrySet()) {
                if (entry.getValue() == bp) {
                    addr = entry.getKey();
                    break;
                }
            }
            String typeStr = temporary ? "Temporary breakpoint" : "Breakpoint";
            return textResult(typeStr + " added at " + symbolName + " (0x" + Long.toHexString(addr) +
                    ", " + moduleName + "+0x" + Long.toHexString(addr - module.base) + ")");
        } catch (Exception e) {
            return errorResult("Failed to add breakpoint by symbol: " + exMsg(e));
        }
    }

    private JSONObject addBreakpointByOffset(JSONObject args) {
        String moduleName = args.getString("module_name");
        long offset = parseAddress(args.getString("offset"));
        boolean temporary = args.containsKey("temporary") && args.getBooleanValue("temporary");
        try {
            Module module = emulator.getMemory().findModule(moduleName);
            if (module == null) {
                return errorResult("Module not found: " + moduleName);
            }
            BreakPoint bp = emulator.attach().addBreakPoint(module, offset);
            if (temporary) {
                bp.setTemporary(true);
            }
            long addr = module.base + offset;
            String typeStr = temporary ? "Temporary breakpoint" : "Breakpoint";
            return textResult(typeStr + " added at " + moduleName + "+0x" + Long.toHexString(offset) +
                    " (0x" + Long.toHexString(addr) + ")");
        } catch (Exception e) {
            return errorResult("Failed to add breakpoint by offset: " + exMsg(e));
        }
    }

    private JSONObject listBreakpoints() {
        try {
            Map<Long, BreakPoint> breakPoints = emulator.attach().getBreakPoints();
            if (breakPoints.isEmpty()) {
                return textResult("No breakpoints set.");
            }
            Memory memory = emulator.getMemory();
            Backend backend = emulator.getBackend();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Total: %d breakpoint(s)%n", breakPoints.size()));
            for (Map.Entry<Long, BreakPoint> entry : breakPoints.entrySet()) {
                long addr = entry.getKey();
                BreakPoint bp = entry.getValue();
                Module module = memory.findModuleByAddress(addr);
                String location;
                if (module != null) {
                    long offset = addr - module.base;
                    location = String.format("%s+0x%x", module.name, offset);
                } else {
                    location = "unknown";
                }
                String temp = bp.isTemporary() ? " [temporary]" : "";
                sb.append(String.format("0x%x  %s%s", addr, location, temp));
                try {
                    byte[] code = backend.mem_read(addr, 4);
                    boolean thumb = emulator.is32Bit() && (addr & 1) != 0;
                    long disAddr = thumb ? (addr & ~1L) : addr;
                    Instruction[] insns = emulator.disassemble(disAddr, code, thumb, 1);
                    if (insns.length > 0) {
                        sb.append(String.format("  ; %s %s", insns[0].getMnemonic(), insns[0].getOpStr()));
                    }
                } catch (Exception ignored) {
                }
                sb.append('\n');
            }
            return textResult(sb.toString());
        } catch (Exception e) {
            return errorResult("Failed to list breakpoints: " + exMsg(e));
        }
    }

    private JSONObject removeBreakpoint(JSONObject args) {
        long address = parseAddress(args.getString("address"));
        try {
            boolean removed = emulator.attach().removeBreakPoint(address);
            if (removed) {
                return textResult("Breakpoint removed at 0x" + Long.toHexString(address));
            } else {
                return errorResult("No breakpoint found at 0x" + Long.toHexString(address));
            }
        } catch (Exception e) {
            return errorResult("Failed to remove breakpoint: " + exMsg(e));
        }
    }

    private JSONObject continueExecution() {
        server.injectCommand("c");
        return textResult("Resumed.");
    }


    private JSONObject stepOver() {
        server.injectCommand("n");
        return textResult("Stepping over.");
    }

    private JSONObject stepInto(JSONObject args) {
        int count = args.containsKey("count") ? args.getIntValue("count") : 1;
        if (count <= 1) {
            server.injectCommand("s");
        } else {
            server.injectCommand("s" + count);
        }
        return textResult("Stepping " + count + " insn.");
    }

    private JSONObject stepOut() {
        if (!server.isDebugIdle()) {
            return errorResult("Emulator is not in debug idle state.");
        }
        try {
            JSONObject result = server.runOnDebuggerThread(() -> {
                Backend backend = emulator.getBackend();
                int lrReg = emulator.is64Bit() ? Arm64Const.UC_ARM64_REG_LR : ArmConst.UC_ARM_REG_LR;
                long lr = backend.reg_read(lrReg).longValue();
                if (emulator.is32Bit()) {
                    lr &= 0xffffffffL;
                }
                BreakPoint bp = emulator.attach().addBreakPoint(lr);
                bp.setTemporary(true);
                return textResult("Temporary breakpoint set at LR=0x" + Long.toHexString(lr));
            });
            if (result.containsKey("isError")) {
                return result;
            }
            server.injectCommand("c");
            String text = result.getJSONArray("content").getJSONObject(0).getString("text");
            return textResult(text + "\nResumed, will break at LR.");
        } catch (Exception e) {
            return errorResult("Step out failed: " + exMsg(e));
        }
    }

    private JSONObject nextBlock() {
        if (!server.isDebugIdle()) {
            return errorResult("Emulator is not in debug idle state.");
        }
        String backendClass = emulator.getBackend().getClass().getSimpleName();
        if (backendClass.contains("Hypervisor") || backendClass.contains("Dynarmic") || backendClass.contains("Kvm")) {
            return errorResult("next_block is not supported on " + backendClass + " backend. Only Unicorn/Unicorn2 backends support BlockHook.");
        }
        server.injectCommand("nb");
        return textResult("Resuming, break at next block.");
    }

    private JSONObject stepUntilMnemonic(JSONObject args) {
        String mnemonic = args.getString("mnemonic");
        if (mnemonic == null || mnemonic.isEmpty()) {
            return errorResult("mnemonic parameter is required.");
        }
        if (!server.isDebugIdle()) {
            return errorResult("Emulator is not in debug idle state.");
        }
        String backendClass = emulator.getBackend().getClass().getSimpleName();
        if (backendClass.contains("Hypervisor") || backendClass.contains("Dynarmic") || backendClass.contains("Kvm")) {
            return errorResult("step_until_mnemonic is not supported on " + backendClass +
                    " backend. Only Unicorn/Unicorn2 backends support per-instruction hook (setFastDebug).");
        }
        server.injectCommand("s" + mnemonic);
        return textResult("Resuming, break on '" + mnemonic + "'.");
    }

    private JSONObject pollEvents(JSONObject args) {
        long timeoutMs = args.containsKey("timeout_ms") ? args.getLongValue("timeout_ms") : 10000;
        java.util.List<JSONObject> events = server.pollEvents(timeoutMs);
        if (events.isEmpty()) {
            return textResult("No events received within timeout.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%d event(s):%n", events.size()));
        for (JSONObject event : events) {
            sb.append(event.toJSONString()).append('\n');
        }
        return textResult(sb.toString());
    }

    private JSONObject traceRead(JSONObject args) {
        long begin = parseAddress(args.getString("begin"));
        long end = parseAddress(args.getString("end"));
        String breakOnStr = args.getString("break_on");
        final long breakOn = breakOnStr != null ? parseAddress(breakOnStr) : -1;
        try {
            if (activeTraceRead != null) {
                activeTraceRead.stopTrace();
                activeTraceRead = null;
            }
            activeTraceRead = emulator.traceRead(begin, end, (emu, address, data, hex) -> {
                JSONObject event = new JSONObject(true);
                event.put("event", "trace_read");
                event.put("pc", "0x" + Long.toHexString(emu.getBackend().reg_read(
                        emu.is64Bit() ? Arm64Const.UC_ARM64_REG_PC : ArmConst.UC_ARM_REG_PC).longValue()));
                event.put("address", "0x" + Long.toHexString(address));
                event.put("size", data.length);
                event.put("hex", hex);
                putModuleInfo(event, emu, address);
                server.queueEvent(event);
                if (breakOn != -1 && address == breakOn) {
                    emu.getBackend().setSingleStep(1);
                }
                return false;
            });
            StringBuilder msg = new StringBuilder("Trace read started: 0x" + Long.toHexString(begin) + " - 0x" + Long.toHexString(end));
            if (breakOn != -1) {
                msg.append(", will break on address 0x").append(Long.toHexString(breakOn));
            }
            msg.append(".");
            return textResult(msg.toString());
        } catch (Exception e) {
            return errorResult("Failed to start trace read: " + e.getClass().getName() + ": " + exMsg(e));
        }
    }

    private JSONObject traceWrite(JSONObject args) {
        long begin = parseAddress(args.getString("begin"));
        long end = parseAddress(args.getString("end"));
        String breakOnStr = args.getString("break_on");
        final long breakOn = breakOnStr != null ? parseAddress(breakOnStr) : -1;
        try {
            if (activeTraceWrite != null) {
                activeTraceWrite.stopTrace();
                activeTraceWrite = null;
            }
            activeTraceWrite = emulator.traceWrite(begin, end, (emu, address, size, value) -> {
                JSONObject event = new JSONObject(true);
                event.put("event", "trace_write");
                event.put("pc", "0x" + Long.toHexString(emu.getBackend().reg_read(
                        emu.is64Bit() ? Arm64Const.UC_ARM64_REG_PC : ArmConst.UC_ARM_REG_PC).longValue()));
                event.put("address", "0x" + Long.toHexString(address));
                event.put("size", size);
                event.put("value", "0x" + Long.toHexString(value));
                putModuleInfo(event, emu, address);
                server.queueEvent(event);
                if (breakOn != -1 && address == breakOn) {
                    emu.getBackend().setSingleStep(1);
                }
                return false;
            });
            StringBuilder msg = new StringBuilder("Trace write started: 0x" + Long.toHexString(begin) + " - 0x" + Long.toHexString(end));
            if (breakOn != -1) {
                msg.append(", will break on address 0x").append(Long.toHexString(breakOn));
            }
            msg.append(".");
            return textResult(msg.toString());
        } catch (Exception e) {
            return errorResult("Failed to start trace write: " + e.getClass().getName() + ": " + exMsg(e));
        }
    }

    private short[] lastTraceWriteRegs;
    private Instruction lastTraceInsn;

    private String formatRegValues(Instruction insn, short[] regs) {
        if (regs == null || regs.length == 0) return null;
        Backend backend = emulator.getBackend();
        StringBuilder sb = new StringBuilder();
        for (short reg : regs) {
            int regId = insn.mapToUnicornReg(reg);
            if (emulator.is32Bit()) {
                if ((regId >= ArmConst.UC_ARM_REG_R0 && regId <= ArmConst.UC_ARM_REG_R12) ||
                        regId == ArmConst.UC_ARM_REG_LR || regId == ArmConst.UC_ARM_REG_SP ||
                        regId == ArmConst.UC_ARM_REG_CPSR) {
                    if (sb.length() > 0) sb.append(", ");
                    if (regId == ArmConst.UC_ARM_REG_CPSR) {
                        Cpsr cpsr = Cpsr.getArm(backend);
                        sb.append(String.format(Locale.US, "cpsr: N=%d, Z=%d, C=%d, V=%d",
                                cpsr.isNegative() ? 1 : 0, cpsr.isZero() ? 1 : 0,
                                cpsr.hasCarry() ? 1 : 0, cpsr.isOverflow() ? 1 : 0));
                    } else {
                        int value = backend.reg_read(regId).intValue();
                        sb.append(insn.regName(reg)).append("=0x").append(Long.toHexString(value & 0xffffffffL));
                    }
                }
            } else {
                if ((regId >= Arm64Const.UC_ARM64_REG_X0 && regId <= Arm64Const.UC_ARM64_REG_X28) ||
                        (regId >= Arm64Const.UC_ARM64_REG_X29 && regId <= Arm64Const.UC_ARM64_REG_SP)) {
                    if (sb.length() > 0) sb.append(", ");
                    if (regId == Arm64Const.UC_ARM64_REG_NZCV) {
                        Cpsr cpsr = Cpsr.getArm64(backend);
                        if (cpsr.isA32()) {
                            sb.append(String.format(Locale.US, "cpsr: N=%d, Z=%d, C=%d, V=%d",
                                    cpsr.isNegative() ? 1 : 0, cpsr.isZero() ? 1 : 0,
                                    cpsr.hasCarry() ? 1 : 0, cpsr.isOverflow() ? 1 : 0));
                        } else {
                            sb.append(String.format(Locale.US, "nzcv: N=%d, Z=%d, C=%d, V=%d",
                                    cpsr.isNegative() ? 1 : 0, cpsr.isZero() ? 1 : 0,
                                    cpsr.hasCarry() ? 1 : 0, cpsr.isOverflow() ? 1 : 0));
                        }
                    } else {
                        long value = backend.reg_read(regId).longValue();
                        sb.append(insn.regName(reg)).append("=0x").append(Long.toHexString(value));
                    }
                } else if (regId >= Arm64Const.UC_ARM64_REG_W0 && regId <= Arm64Const.UC_ARM64_REG_W30) {
                    if (sb.length() > 0) sb.append(", ");
                    int value = backend.reg_read(regId).intValue();
                    sb.append(insn.regName(reg)).append("=0x").append(Long.toHexString(value & 0xffffffffL));
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private JSONObject traceCode(JSONObject args) {
        long begin = parseAddress(args.getString("begin"));
        long end = parseAddress(args.getString("end"));
        String breakOnStr = args.getString("break_on");
        final long breakOn = breakOnStr != null ? parseAddress(breakOnStr) : -1;
        try {
            if (activeTraceCode != null) {
                activeTraceCode.stopTrace();
                activeTraceCode = null;
            }
            lastTraceWriteRegs = null;
            lastTraceInsn = null;
            activeTraceCode = emulator.traceCode(begin, end, (emu, address, insn) -> {
                JSONObject event = new JSONObject(true);
                event.put("event", "trace_code");
                event.put("address", "0x" + Long.toHexString(address));
                if (insn != null) {
                    event.put("mnemonic", insn.getMnemonic());
                    event.put("operands", insn.getOpStr());
                    event.put("size", insn.getSize());
                }
                Module module = emu.getMemory().findModuleByAddress(address);
                if (module != null) {
                    event.put("module", module.name);
                    event.put("offset", "0x" + Long.toHexString(address - module.base));
                }
                if (lastTraceWriteRegs != null && lastTraceInsn != null) {
                    String writeValues = formatRegValues(lastTraceInsn, lastTraceWriteRegs);
                    if (writeValues != null) {
                        event.put("prev_write", writeValues);
                    }
                }
                if (insn != null) {
                    RegsAccess regsAccess = insn.regsAccess();
                    if (regsAccess != null) {
                        String readValues = formatRegValues(insn, regsAccess.getRegsRead());
                        if (readValues != null) {
                            event.put("regs_read", readValues);
                        }
                        short[] regsWrite = regsAccess.getRegsWrite();
                        if (regsWrite != null && regsWrite.length > 0) {
                            lastTraceWriteRegs = regsWrite;
                            lastTraceInsn = insn;
                        } else {
                            lastTraceWriteRegs = null;
                            lastTraceInsn = null;
                        }
                    } else {
                        lastTraceWriteRegs = null;
                        lastTraceInsn = null;
                    }
                }
                server.queueEvent(event);
                if (breakOn != -1 && address == breakOn) {
                    emu.attach().debug("trace_code break_on address hit: 0x" + Long.toHexString(address));
                }
            });
            StringBuilder msg = new StringBuilder("Trace code started: 0x" + Long.toHexString(begin) + " - 0x" + Long.toHexString(end));
            if (breakOn != -1) {
                msg.append(", will break on PC 0x").append(Long.toHexString(breakOn));
            }
            msg.append(".");
            return textResult(msg.toString());
        } catch (Exception e) {
            return errorResult("Failed to start trace code: " + e.getClass().getName() + ": " + exMsg(e));
        }
    }


    private JSONObject getCallstack() {
        try {
            Unwinder unwinder = emulator.getUnwinder();
            Memory memory = emulator.getMemory();
            java.util.List<Frame> frames = unwinder.getFrames(50);
            if (frames.isEmpty()) {
                return textResult("No call stack frames available.");
            }
            StringBuilder sb = new StringBuilder();
            GccDemangler demangler = DemanglerFactory.createDemangler();
            for (int i = 0; i < frames.size(); i++) {
                long pc = frames.get(i).ip.peer;
                Module module = memory.findModuleByAddress(pc);
                sb.append(String.format("#%-3d 0x%x", i, pc));
                if (module != null) {
                    sb.append(String.format("  %s+0x%x", module.name, pc - module.base));
                    Symbol symbol = module.findClosestSymbolByAddress(pc, false);
                    if (symbol != null && pc - symbol.getAddress() <= Unwinder.SYMBOL_SIZE) {
                        sb.append(String.format("  (%s+0x%x)", demangler.demangle(symbol.getName()), pc - symbol.getAddress()));
                    }
                }
                sb.append('\n');
            }
            return textResult(sb.toString());
        } catch (Exception e) {
            return errorResult("Failed to get callstack: " + e.getClass().getName() + ": " + exMsg(e));
        }
    }

    private JSONObject findSymbol(JSONObject args) {
        String moduleName = args.getString("module_name");
        String symbolName = args.getString("symbol_name");
        String addressStr = args.getString("address");
        try {
            if (addressStr != null && !addressStr.isEmpty()) {
                long address = parseAddress(addressStr);
                Module module = emulator.getMemory().findModuleByAddress(address);
                if (module == null) {
                    return errorResult("No module found at address 0x" + Long.toHexString(address));
                }
                Symbol symbol = module.findClosestSymbolByAddress(address, false);
                if (symbol == null || address - symbol.getAddress() > Unwinder.SYMBOL_SIZE) {
                    return textResult("No symbol found near 0x" + Long.toHexString(address) +
                            " (in " + module.name + "+0x" + Long.toHexString(address - module.base) + ")");
                }
                GccDemangler demangler = DemanglerFactory.createDemangler();
                return textResult("0x" + Long.toHexString(address) + " = " +
                        module.name + "!" + demangler.demangle(symbol.getName()) +
                        "+0x" + Long.toHexString(address - symbol.getAddress()));
            }
            if (moduleName != null && symbolName != null) {
                Module module = emulator.getMemory().findModule(moduleName);
                if (module == null) {
                    return errorResult("Module not found: " + moduleName);
                }
                Symbol symbol = module.findSymbolByName(symbolName, false);
                if (symbol == null) {
                    return errorResult("Symbol '" + symbolName + "' not found in " + moduleName);
                }
                GccDemangler demangler = DemanglerFactory.createDemangler();
                return textResult(demangler.demangle(symbol.getName()) +
                        " @ 0x" + Long.toHexString(symbol.getAddress()) +
                        " (" + moduleName + "+0x" + Long.toHexString(symbol.getAddress() - module.base) + ")");
            }
            return errorResult("Provide either (module_name + symbol_name) or (address).");
        } catch (Exception e) {
            return errorResult("Find symbol failed: " + e.getClass().getName() + ": " + exMsg(e));
        }
    }

    private JSONObject readString(JSONObject args) {
        long address = parseAddress(args.getString("address"));
        int maxLength = args.containsKey("max_length") ? args.getIntValue("max_length") : 256;
        try {
            byte[] data = emulator.getBackend().mem_read(address, maxLength);
            int len = 0;
            while (len < data.length && data[len] != 0) {
                len++;
            }
            String str = new String(data, 0, len, java.nio.charset.StandardCharsets.UTF_8);
            String result = "\"" + str + "\" (" + len + "B)";
            if (len == maxLength) {
                result += " [truncated]";
            }
            return textResult(result);
        } catch (Exception e) {
            return errorResult("Failed to read string at 0x" + Long.toHexString(address) + ": " + exMsg(e));
        }
    }

    private JSONObject readStdString(JSONObject args) {
        long address = parseAddress(args.getString("address"));
        try {
            UnidbgPointer pointer = UnidbgPointer.pointer(emulator, address);
            if (pointer == null) {
                return errorResult("Null pointer for address 0x" + Long.toHexString(address));
            }
            com.github.unidbg.unix.struct.StdString stdStr =
                    com.github.unidbg.unix.struct.StdString.createStdString(emulator, pointer);
            long dataSize = stdStr.getDataSize();
            boolean isTiny = (emulator.getBackend().mem_read(address, 1)[0] & 1) == 0;
            byte[] data = stdStr.getData(emulator);
            String str = new String(data, java.nio.charset.StandardCharsets.UTF_8);

            String result = "\"" + str + "\" (" + dataSize + "B, " + (isTiny ? "SSO" : "heap") + ")";
            return textResult(result);
        } catch (Exception e) {
            return errorResult("Failed to read std::string at 0x" + Long.toHexString(address) + ": " + exMsg(e));
        }
    }

    private void appendModuleAndSymbol(StringBuilder sb, Memory memory, GccDemangler demangler, long address) {
        Module module = memory.findModuleByAddress(address);
        if (module != null) {
            sb.append(String.format("  (%s+0x%x)", module.name, address - module.base));
            Symbol symbol = module.findClosestSymbolByAddress(address, false);
            if (symbol != null && address - symbol.getAddress() <= Unwinder.SYMBOL_SIZE) {
                sb.append(String.format("  <%s>", demangler.demangle(symbol.getName())));
            }
        }
    }

    private JSONObject readPointer(JSONObject args) {
        long address = parseAddress(args.getString("address"));
        int depth = args.containsKey("depth") ? args.getIntValue("depth") : 1;
        int offset = args.containsKey("offset") ? args.getIntValue("offset") : 0;
        boolean is64 = emulator.is64Bit();
        int ptrSize = is64 ? 8 : 4;
        Backend backend = emulator.getBackend();
        Memory memory = emulator.getMemory();
        GccDemangler demangler = DemanglerFactory.createDemangler();

        StringBuilder sb = new StringBuilder();
        long currentAddr = address;
        try {
            for (int level = 0; level <= depth; level++) {
                sb.append(String.format("[%d] 0x%x", level, currentAddr));
                appendModuleAndSymbol(sb, memory, demangler, currentAddr);
                sb.append('\n');

                if (level < depth) {
                    long readAddr = currentAddr + offset;
                    byte[] data = backend.mem_read(readAddr, ptrSize);
                    long ptrValue;
                    ptrValue = 0;
                    if (is64) {
                        for (int i = 7; i >= 0; i--) {
                            ptrValue = (ptrValue << 8) | (data[i] & 0xFFL);
                        }
                    } else {
                        for (int i = 3; i >= 0; i--) {
                            ptrValue = (ptrValue << 8) | (data[i] & 0xFFL);
                        }
                    }
                    if (offset != 0) {
                        sb.append(String.format("    -> read at 0x%x+0x%x = 0x%x%n", currentAddr, offset, ptrValue));
                    } else {
                        sb.append(String.format("    -> 0x%x%n", ptrValue));
                    }
                    if (ptrValue == 0) {
                        sb.append("    (null pointer, chain ends)\n");
                        break;
                    }
                    currentAddr = ptrValue;
                }
            }
        } catch (Exception e) {
            sb.append(String.format("    (read failed at 0x%x: %s)%n", currentAddr, exMsg(e)));
        }
        return textResult(sb.toString());
    }

    private JSONObject readTyped(JSONObject args) {
        long address = parseAddress(args.getString("address"));
        String rawType = args.getString("type");
        if (rawType == null || rawType.isEmpty()) {
            return errorResult("Missing required parameter 'type'. Supported: int8, uint8, int16, uint16, int32, uint32, int64, uint64, float, double, pointer");
        }
        String type = rawType.toLowerCase();
        int count = args.containsKey("count") ? args.getIntValue("count") : 1;

        int elemSize;
        switch (type) {
            case "int8": case "uint8": elemSize = 1; break;
            case "int16": case "uint16": elemSize = 2; break;
            case "int32": case "uint32": case "float": elemSize = 4; break;
            case "int64": case "uint64": case "double": elemSize = 8; break;
            case "pointer": elemSize = emulator.is64Bit() ? 8 : 4; break;
            default: return errorResult("Unsupported type: " + type + ". Supported: int8, uint8, int16, uint16, int32, uint32, int64, uint64, float, double, pointer");
        }

        try {
            byte[] data = emulator.getBackend().mem_read(address, (long) elemSize * count);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            Memory memory = emulator.getMemory();
            GccDemangler demangler = DemanglerFactory.createDemangler();
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < count; i++) {
                long elemAddr = address + (long) i * elemSize;
                sb.append(String.format("[%d] 0x%x: ", i, elemAddr));
                switch (type) {
                    case "int8": sb.append(data[i]); break;
                    case "uint8": sb.append(data[i] & 0xFF); break;
                    case "int16": sb.append(buf.getShort(i * 2)); break;
                    case "uint16": sb.append(buf.getShort(i * 2) & 0xFFFF); break;
                    case "int32": sb.append(buf.getInt(i * 4)); break;
                    case "uint32": sb.append(Integer.toUnsignedString(buf.getInt(i * 4))); break;
                    case "float": sb.append(buf.getFloat(i * 4)); break;
                    case "int64": sb.append(buf.getLong(i * 8)); break;
                    case "uint64": sb.append(Long.toUnsignedString(buf.getLong(i * 8))); break;
                    case "double": sb.append(buf.getDouble(i * 8)); break;
                    case "pointer": {
                        long ptrVal = emulator.is64Bit() ? buf.getLong(i * 8) : (buf.getInt(i * 4) & 0xFFFFFFFFL);
                        sb.append("0x").append(Long.toHexString(ptrVal));
                        if (ptrVal != 0) {
                            appendModuleAndSymbol(sb, memory, demangler, ptrVal);
                        }
                        break;
                    }
                }
                sb.append('\n');
            }
            return textResult(sb.toString());
        } catch (Exception e) {
            return errorResult("Failed to read typed data at 0x" + Long.toHexString(address) + ": " + exMsg(e));
        }
    }

    private JSONObject callFunction(JSONObject args) {
        if (emulator.isRunning()) {
            return errorResult("Cannot call function while emulator is running.");
        }
        long address = parseAddress(args.getString("address"));
        return doCallFunction(address, args);
    }

    private JSONObject callSymbol(JSONObject args) {
        if (emulator.isRunning()) {
            return errorResult("Cannot call function while emulator is running.");
        }
        String moduleName = args.getString("module_name");
        String symbolName = args.getString("symbol_name");
        if (moduleName == null || moduleName.isEmpty()) {
            return errorResult("Missing required parameter 'module_name'.");
        }
        if (symbolName == null || symbolName.isEmpty()) {
            return errorResult("Missing required parameter 'symbol_name'.");
        }
        Module module = emulator.getMemory().findModule(moduleName);
        if (module == null) {
            return errorResult("Module not found: " + moduleName);
        }
        Symbol symbol = module.findSymbolByName(symbolName, false);
        if (symbol == null) {
            symbol = module.findSymbolByName("_" + symbolName, false);
        }
        if (symbol == null) {
            return errorResult("Symbol '" + symbolName + "' not found in " + moduleName +
                    ". Use list_exports to see available symbols.");
        }
        return doCallFunction(symbol.getAddress(), args);
    }

    private JSONObject doCallFunction(long address, JSONObject args) {
        JSONArray argsArray = args.getJSONArray("args");
        Object[] funcArgs;
        if (argsArray == null || argsArray.isEmpty()) {
            funcArgs = new Object[0];
        } else {
            funcArgs = new Object[argsArray.size()];
            for (int i = 0; i < argsArray.size(); i++) {
                String argStr = argsArray.getString(i);
                try {
                    funcArgs[i] = parseCallArg(argStr);
                } catch (Exception e) {
                    return errorResult("Invalid argument[" + i + "] '" + argStr + "': " + exMsg(e));
                }
            }
        }

        StringBuilder sb = new StringBuilder();

        int previewSize = args.containsKey("preview_size") ? args.getIntValue("preview_size") : 64;

        try {
            Number result = Module.emulateFunction(emulator, address, funcArgs);
            long retVal = result.longValue();
            sb.append("ret=0x").append(Long.toHexString(retVal));
            if (retVal != 0 && retVal < 0x100000) {
                sb.append(" (").append(retVal).append(')');
            }

            Memory memory = emulator.getMemory();
            Module retModule = memory.findModuleByAddress(retVal);
            if (retModule != null) {
                GccDemangler demangler = DemanglerFactory.createDemangler();
                sb.append(' ').append(retModule.name).append("+0x").append(Long.toHexString(retVal - retModule.base));
                Symbol sym = retModule.findClosestSymbolByAddress(retVal, false);
                if (sym != null && retVal - sym.getAddress() <= Unwinder.SYMBOL_SIZE) {
                    sb.append(" <").append(demangler.demangle(sym.getName())).append('>');
                }
            }

            if (retVal > 0x1000 && previewSize > 0) {
                try {
                    byte[] previewData = emulator.getBackend().mem_read(retVal, previewSize);
                    String str = tryPrintableString(previewData);
                    if (str != null) {
                        sb.append(" \"").append(str).append('"');
                    }
                } catch (Exception ignored) {
                }
            }
            sb.append('\n');
            return textResult(sb.toString());
        } catch (Exception e) {
            sb.append("\nCall FAILED: ").append(e.getClass().getName()).append(": ").append(exMsg(e)).append('\n');
            Throwable cause = e.getCause();
            if (cause != null) {
                sb.append("Caused by: ").append(cause.getClass().getName()).append(": ").append(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName()).append('\n');
            }
            return errorResult(sb.toString());
        }
    }

    private Object parseCallArg(String argStr) throws DecoderException {
        if (argStr == null || "null".equalsIgnoreCase(argStr)) {
            return null;
        }
        if (argStr.startsWith("s:")) {
            return argStr.substring(2);
        }
        if (argStr.startsWith("b:")) {
            return Hex.decodeHex(argStr.substring(2).toCharArray());
        }
        return parseAddress(argStr);
    }

    private JSONObject listModules(JSONObject args) {
        String filter = args != null ? args.getString("filter") : null;
        Collection<Module> modules = emulator.getMemory().getLoadedModules();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Module m : modules) {
            if (filter != null && !filter.isEmpty() && !m.name.toLowerCase().contains(filter.toLowerCase())) {
                continue;
            }
            sb.append(m.name).append(" 0x").append(Long.toHexString(m.base))
                    .append(" 0x").append(Long.toHexString(m.size)).append('\n');
            count++;
        }
        sb.insert(0, count + " modules" + (filter != null && !filter.isEmpty() ? " (filter: '" + filter + "', total: " + modules.size() + ")" : "") + ":\n");
        return textResult(sb.toString());
    }

    private JSONObject getModuleInfo(JSONObject args) {
        String moduleName = args.getString("module_name");
        Module module = emulator.getMemory().findModule(moduleName);
        if (module == null) {
            return errorResult("Module not found: " + moduleName);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(module.name).append(" 0x").append(Long.toHexString(module.base))
                .append(" size=0x").append(Long.toHexString(module.size)).append('\n');
        Collection<Symbol> exports = module.getExportedSymbols();
        sb.append("exports=").append(exports.size());
        Collection<Module> deps = module.getNeededLibraries();
        if (!deps.isEmpty()) {
            sb.append(" deps=");
            boolean first = true;
            for (Module dep : deps) {
                if (!first) sb.append(',');
                sb.append(dep.name);
                first = false;
            }
        }
        sb.append('\n');
        return textResult(sb.toString());
    }

    private JSONObject executeCustomTool(CustomTool tool, JSONObject args) {
        StringBuilder cmd = new StringBuilder("run ");
        cmd.append(tool.name);
        for (String pn : tool.paramNames) {
            String val = args.getString(pn);
            if (val != null) {
                cmd.append(' ').append(val);
            }
        }
        server.injectCommand(cmd.toString());
        return textResult("Emulation started: " + tool.name);
    }

    private static final int MAX_EXPORT_LINES = 200;

    private JSONObject listExports(JSONObject args) {
        String moduleName = args.getString("module_name");
        String filter = args.getString("filter");
        try {
            Module module = emulator.getMemory().findModule(moduleName);
            if (module == null) {
                return errorResult("Module not found: " + moduleName);
            }
            Collection<Symbol> symbols = module.getExportedSymbols();
            if (symbols.isEmpty()) {
                return textResult("No exported symbols in " + moduleName);
            }
            GccDemangler demangler = DemanglerFactory.createDemangler();
            List<String> lines = new ArrayList<>();
            for (Symbol symbol : symbols) {
                if (filter != null && !filter.isEmpty()) {
                    String name = symbol.getName();
                    String demangled = demangler.demangle(name);
                    if (!name.toLowerCase().contains(filter.toLowerCase()) &&
                            !demangled.toLowerCase().contains(filter.toLowerCase())) {
                        continue;
                    }
                }
                long addr = symbol.getAddress();
                String demangled = demangler.demangle(symbol.getName());
                String line = "+0x" + Long.toHexString(addr - module.base) + " " +
                        (demangled.equals(symbol.getName()) ? symbol.getName() : demangled);
                lines.add(line);
            }
            StringBuilder sb = new StringBuilder();
            boolean truncated = lines.size() > MAX_EXPORT_LINES && (filter == null || filter.isEmpty());
            if (filter != null && !filter.isEmpty()) {
                sb.append(String.format("Showing %d of %d symbols (filter: '%s')%n", lines.size(), symbols.size(), filter));
            } else {
                sb.append(String.format("%d exported symbol(s)%s:%n", lines.size(),
                        truncated ? " (showing first " + MAX_EXPORT_LINES + ", use filter to narrow)" : ""));
            }
            int limit = truncated ? MAX_EXPORT_LINES : lines.size();
            for (int i = 0; i < limit; i++) {
                sb.append(lines.get(i)).append('\n');
            }
            if (truncated) {
                sb.append("... ").append(lines.size() - MAX_EXPORT_LINES).append(" more symbols omitted. Use filter parameter to search.\n");
            }
            return textResult(sb.toString());
        } catch (Exception e) {
            return errorResult("Failed to list exports: " + e.getClass().getName() + ": " + exMsg(e));
        }
    }

    private JSONObject getThreads() {
        try {
            List<Task> tasks = emulator.getThreadDispatcher().getTaskList();
            if (tasks.isEmpty()) {
                return textResult("No threads/tasks.");
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%d thread(s):%n", tasks.size()));
            for (Task task : tasks) {
                sb.append(String.format("  tid=%d: %s%n", task.getId(), task));
            }
            return textResult(sb.toString());
        } catch (Exception e) {
            return errorResult("Failed to get threads: " + exMsg(e));
        }
    }

    private JSONObject allocateMemory(JSONObject args) {
        String hexData = args.getString("data");
        byte[] initData = null;
        if (hexData != null && !hexData.isEmpty()) {
            try {
                initData = Hex.decodeHex(hexData.toCharArray());
            } catch (DecoderException e) {
                return errorResult("Invalid 'data' hex string: \"" + hexData + "\". Expected hex-encoded bytes, e.g. \"48656c6c6f\" for \"Hello\".");
            }
        }
        int size = args.containsKey("size") ? args.getIntValue("size") : 0;
        if (size <= 0 && initData != null) {
            size = initData.length;
        }
        if (size <= 0) {
            return errorResult("Size must be positive. Provide 'size' or 'data' (hex-encoded bytes to infer size from).");
        }
        if (initData != null && initData.length > size) {
            return errorResult("Data length (" + initData.length + " bytes) exceeds allocation size (" + size + " bytes).");
        }
        boolean isRunning = emulator.isRunning();
        Boolean runtimeParam = args.containsKey("runtime") ? args.getBoolean("runtime") : null;
        boolean runtime;
        if (isRunning) {
            if (runtimeParam != null && !runtimeParam) {
                return errorResult("Cannot use runtime=false (libc malloc) while emulator is running. " +
                        "Use runtime=true (mmap) or omit the parameter.");
            }
            runtime = true;
        } else {
            runtime = runtimeParam != null ? runtimeParam : false;
        }
        try {
            MemoryBlock block = emulator.getMemory().malloc(size, runtime);
            UnidbgPointer pointer = block.getPointer();
            allocatedBlocks.put(pointer.peer, new Allocation(block, runtime, size));
            if (initData != null) {
                pointer.write(0, initData, 0, initData.length);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("0x").append(Long.toHexString(pointer.peer))
                    .append(" (").append(size).append(" bytes, ").append(runtime ? "mmap" : "malloc").append(')');
            if (initData != null) {
                sb.append(" +data");
            }
            return textResult(sb.toString());
        } catch (Exception e) {
            return errorResult("Failed to allocate memory: " + exMsg(e));
        }
    }

    private JSONObject freeMemory(JSONObject args) {
        long address = parseAddress(args.getString("address"));
        Allocation alloc = allocatedBlocks.get(address);
        if (alloc == null) {
            return errorResult("No tracked allocation at 0x" + Long.toHexString(address) +
                    ". Only blocks allocated via allocate_memory can be freed.");
        }
        if (!alloc.runtime && emulator.isRunning()) {
            return errorResult("Cannot free malloc-allocated memory at 0x" + Long.toHexString(address) +
                    " while emulator is running. malloc blocks require isRunning=false to call libc free()." +
                    " Wait until emulator stops first.");
        }
        try {
            alloc.block.free();
            allocatedBlocks.remove(address);
            return textResult("Freed 0x" + Long.toHexString(address));
        } catch (Exception e) {
            return errorResult("Failed to free memory at 0x" + Long.toHexString(address) + ": " + exMsg(e));
        }
    }

    private JSONObject listAllocations() {
        if (allocatedBlocks.isEmpty()) {
            return textResult("No active allocations.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%d active allocation(s):%n", allocatedBlocks.size()));
        for (Map.Entry<Long, Allocation> entry : allocatedBlocks.entrySet()) {
            long addr = entry.getKey();
            Allocation alloc = entry.getValue();
            String type = alloc.runtime ? "mmap (free anytime)" : "malloc (free requires isRunning=false)";
            sb.append(String.format("  0x%x  size=%d (0x%x)  type=%s%n", addr, alloc.size, alloc.size, type));
        }
        return textResult(sb.toString());
    }

    private JSONObject getObjcClassName(JSONObject args) {
        if (emulator.getFamily() != Family.iOS) {
            return errorResult("get_objc_class_name is only available on iOS emulators.");
        }
        long address = parseAddress(args.getString("address"));
        if (address == 0) {
            return errorResult("Address is null (0x0).");
        }
        try {
            String className = emulator.getObjcClassName(address);
            if (className != null) {
                return textResult("0x" + Long.toHexString(address) + " -> " + className);
            } else {
                return errorResult("Failed to resolve ObjC class name at 0x" + Long.toHexString(address));
            }
        } catch (Exception e) {
            return errorResult("Failed to read ObjC class at 0x" + Long.toHexString(address) + ": " + exMsg(e));
        }
    }

    private JSONObject inspectObjcMsg() {
        if (emulator.getFamily() != Family.iOS) {
            return errorResult("inspect_objc_msg is only available on iOS emulators.");
        }
        if (!emulator.is64Bit()) {
            return errorResult("inspect_objc_msg currently only supports ARM64.");
        }
        try {
            Backend backend = emulator.getBackend();
            long x0 = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).longValue();
            long x1 = backend.reg_read(Arm64Const.UC_ARM64_REG_X1).longValue();

            StringBuilder sb = new StringBuilder();
            String className = null;
            if (x0 != 0) {
                try {
                    className = emulator.getObjcClassName(x0);
                } catch (Exception ignored) {
                }
            }

            String selector = null;
            if (x1 != 0) {
                try {
                    byte[] selData = backend.mem_read(x1, 256);
                    int len = 0;
                    while (len < selData.length && selData[len] != 0) len++;
                    selector = new String(selData, 0, len, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
            }

            if (className != null && selector != null) {
                sb.append(String.format("-[%s %s]%n", className, selector));
            }

            sb.append(String.format("X0 (receiver): 0x%x", x0));
            if (className != null) {
                sb.append("  class: ").append(className);
            } else if (x0 == 0) {
                sb.append("  (nil)");
            } else {
                sb.append("  (class name not resolved)");
            }
            sb.append('\n');

            sb.append(String.format("X1 (selector): 0x%x", x1));
            if (selector != null) {
                sb.append("  \"").append(selector).append('"');
            } else if (x1 == 0) {
                sb.append("  (nil)");
            }
            sb.append('\n');

            for (int i = 2; i <= 7; i++) {
                long val = backend.reg_read(Arm64Const.UC_ARM64_REG_X0 + i).longValue();
                if (val != 0) {
                    sb.append(String.format("X%d (arg%d):     0x%x", i, i - 2, val));
                    Module module = emulator.getMemory().findModuleByAddress(val);
                    if (module != null) {
                        sb.append("  (").append(module.name).append("+0x").append(Long.toHexString(val - module.base)).append(')');
                    } else if (val > 0x1000) {
                        try {
                            String s = tryPrintableString(backend.mem_read(val, 64));
                            if (s != null) {
                                sb.append("  \"").append(s).append('"');
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    sb.append('\n');
                }
            }

            return textResult(sb.toString());
        } catch (Exception e) {
            return errorResult("Failed to inspect objc_msgSend: " + exMsg(e));
        }
    }

    private JSONObject dumpObjcClass(JSONObject args) {
        if (emulator.isRunning()) {
            return errorResult("Cannot call dump_objc_class while emulator is running. " +
                    "This tool calls ObjC runtime methods internally and requires the emulator to be stopped.");
        }
        if (emulator.getFamily() != Family.iOS) {
            return errorResult("dump_objc_class is only available on iOS emulators. Current family: " + emulator.getFamily());
        }
        String className = args.getString("class_name");
        if (className == null || className.isEmpty()) {
            return errorResult("class_name parameter is required.");
        }
        try {
            String classDef = emulator.dumpObjcClass(className);
            if (classDef == null || classDef.isEmpty()) {
                return errorResult("Class '" + className + "' not found or returned empty definition. " +
                        "Make sure the class exists in the ObjC runtime.");
            }
            return textResult(classDef);
        } catch (UnsupportedOperationException e) {
            return errorResult("ObjC class dump not supported: " + exMsg(e));
        } catch (Exception e) {
            return errorResult("Failed to dump ObjC class '" + className + "': " +
                    e.getClass().getSimpleName() + ": " + exMsg(e));
        }
    }

    private JSONObject dumpGpbProtobuf(JSONObject args) {
        if (emulator.isRunning()) {
            return errorResult("Cannot call dump_gpb_protobuf while emulator is running. " +
                    "This tool calls ObjC runtime methods internally and requires the emulator to be stopped.");
        }
        if (emulator.getFamily() != Family.iOS) {
            return errorResult("dump_gpb_protobuf is only available on iOS emulators. Current family: " + emulator.getFamily());
        }
        if (!emulator.is64Bit()) {
            return errorResult("dump_gpb_protobuf is only available on 64-bit iOS emulators.");
        }
        String className = args.getString("class_name");
        if (className == null || className.isEmpty()) {
            return errorResult("class_name parameter is required.");
        }
        try {
            String protoDef = emulator.dumpGPBProtobufDef(className);
            return textResult(protoDef);
        } catch (UnsupportedOperationException e) {
            return errorResult("GPB protobuf dump not supported: " + exMsg(e) +
                    ". Ensure the Google Protobuf Objective-C runtime (GPB) library is loaded and the class '" +
                    className + "' is a GPBMessage subclass that responds to 'descriptor'.");
        } catch (Exception e) {
            return errorResult("Failed to dump GPB protobuf for '" + className + "': " +
                    e.getClass().getSimpleName() + ": " + exMsg(e));
        }
    }

    private static int resolvePermission(String permission) {
        if (permission == null || permission.isEmpty() || "write".equalsIgnoreCase(permission)) {
            return UnicornConst.UC_PROT_WRITE;
        }
        if ("read".equalsIgnoreCase(permission)) {
            return UnicornConst.UC_PROT_READ;
        }
        if ("execute".equalsIgnoreCase(permission)) {
            return UnicornConst.UC_PROT_EXEC;
        }
        return UnicornConst.UC_PROT_WRITE;
    }

    private Keystone createKeystone() {
        if (emulator.is64Bit()) {
            return new Keystone(KeystoneArchitecture.Arm64, KeystoneMode.LittleEndian);
        } else {
            boolean thumb = ARM.isThumb(emulator.getBackend());
            return new Keystone(KeystoneArchitecture.Arm, thumb ? KeystoneMode.ArmThumb : KeystoneMode.Arm);
        }
    }

    private int resolveRegister(String name) {
        if (emulator.is64Bit()) {
            if (name.startsWith("X")) {
                int num = Integer.parseInt(name.substring(1));
                if (num >= 0 && num <= 28) {
                    return Arm64Const.UC_ARM64_REG_X0 + num;
                } else if (num == 29) {
                    return Arm64Const.UC_ARM64_REG_FP;
                } else if (num == 30) {
                    return Arm64Const.UC_ARM64_REG_LR;
                }
                throw new IllegalArgumentException("Invalid X register number: " + num);
            }
            if (name.startsWith("W")) {
                int num = Integer.parseInt(name.substring(1));
                if (num >= 0 && num <= 30) {
                    return Arm64Const.UC_ARM64_REG_W0 + num;
                }
                throw new IllegalArgumentException("Invalid W register number: " + num);
            }
            switch (name) {
                case "SP": return Arm64Const.UC_ARM64_REG_SP;
                case "PC": return Arm64Const.UC_ARM64_REG_PC;
                case "LR": return Arm64Const.UC_ARM64_REG_LR;
                case "FP": return Arm64Const.UC_ARM64_REG_FP;
                default: throw new IllegalArgumentException("Unknown ARM64 register: " + name);
            }
        } else {
            if (name.startsWith("R")) {
                int num = Integer.parseInt(name.substring(1));
                if (num >= 0 && num <= 12) {
                    return ArmConst.UC_ARM_REG_R0 + num;
                } else if (num == 13) {
                    return ArmConst.UC_ARM_REG_SP;
                } else if (num == 14) {
                    return ArmConst.UC_ARM_REG_LR;
                } else if (num == 15) {
                    return ArmConst.UC_ARM_REG_PC;
                }
                throw new IllegalArgumentException("Invalid R register number: " + num);
            }
            switch (name) {
                case "SP": return ArmConst.UC_ARM_REG_SP;
                case "PC": return ArmConst.UC_ARM_REG_PC;
                case "LR": return ArmConst.UC_ARM_REG_LR;
                case "FP": return ArmConst.UC_ARM_REG_FP;
                case "IP": return ArmConst.UC_ARM_REG_IP;
                default: throw new IllegalArgumentException("Unknown ARM register: " + name);
            }
        }
    }

    private static long parseAddress(String address) {
        if (address == null) return 0;
        address = address.trim();
        if (address.startsWith("0x") || address.startsWith("0X")) {
            return Long.parseUnsignedLong(address.substring(2), 16);
        }
        return Long.parseUnsignedLong(address, 16);
    }

    private static String permString(int prot) {
        return ((prot & 1) != 0 ? "r" : "-") +
                ((prot & 2) != 0 ? "w" : "-") +
                ((prot & 4) != 0 ? "x" : "-");
    }

    private static JSONObject textResult(String text) {
        JSONObject result = new JSONObject(true);
        JSONArray content = new JSONArray();
        JSONObject item = new JSONObject(true);
        item.put("type", "text");
        item.put("text", text);
        content.add(item);
        result.put("content", content);
        return result;
    }

    static JSONObject errorResult(String message) {
        JSONObject result = textResult(message);
        result.put("isError", true);
        return result;
    }

    private static String tryPrintableString(byte[] data) {
        int len = 0;
        while (len < data.length && data[len] != 0) {
            if (data[len] < 0x20 || data[len] > 0x7e) return null;
            len++;
        }
        return len > 0 ? new String(data, 0, len, java.nio.charset.StandardCharsets.UTF_8) : null;
    }

    private static String exMsg(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            return e.getClass().getName();
        }
        return msg;
    }

    private static JSONObject toolSchema(String name, String description, JSONObject... params) {
        JSONObject schema = new JSONObject(true);
        schema.put("name", name);
        schema.put("description", description);
        JSONObject inputSchema = new JSONObject(true);
        inputSchema.put("type", "object");
        if (params.length > 0) {
            JSONObject properties = new JSONObject(true);
            for (JSONObject p : params) {
                properties.put(p.getString("_name"), p);
                p.remove("_name");
            }
            inputSchema.put("properties", properties);
        }
        schema.put("inputSchema", inputSchema);
        return schema;
    }

    private static void putModuleInfo(JSONObject event, Emulator<?> emu, long address) {
        Module module = emu.getMemory().findModuleByAddress(address);
        if (module != null) {
            event.put("module", module.name);
            event.put("offset", "0x" + Long.toHexString(address - module.base));
        }
    }

    private static JSONObject buildInputSchema(String... paramNames) {
        JSONObject inputSchema = new JSONObject(true);
        inputSchema.put("type", "object");
        if (paramNames.length > 0) {
            JSONObject properties = new JSONObject(true);
            JSONArray required = new JSONArray();
            for (String pn : paramNames) {
                JSONObject p = new JSONObject(true);
                p.put("type", "string");
                properties.put(pn, p);
                required.add(pn);
            }
            inputSchema.put("properties", properties);
            inputSchema.put("required", required);
        }
        return inputSchema;
    }

    private static JSONObject param(String name, String type, String description) {
        JSONObject p = new JSONObject(true);
        p.put("_name", name);
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    private static JSONObject argsParam() {
        JSONObject p = new JSONObject(true);
        p.put("_name", "args");
        p.put("type", "array");
        JSONObject items = new JSONObject(true);
        items.put("type", "string");
        p.put("items", items);
        p.put("description", "Args array, format in instructions.");
        return p;
    }

    private static class CustomTool {
        final String name;
        final String description;
        final String[] paramNames;

        CustomTool(String name, String description, String[] paramNames) {
            this.name = name;
            this.description = description;
            this.paramNames = paramNames != null ? paramNames : new String[0];
        }
    }
}
