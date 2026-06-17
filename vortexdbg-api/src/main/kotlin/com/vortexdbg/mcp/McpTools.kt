package com.vortexdbg.mcp

import capstone.api.Instruction
import capstone.api.RegsAccess
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.Family
import com.vortexdbg.Module
import com.vortexdbg.Symbol
import com.vortexdbg.TraceHook
import com.vortexdbg.arm.ARM
import com.vortexdbg.arm.Cpsr
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.debugger.BreakPoint
import com.vortexdbg.debugger.Debugger
import com.vortexdbg.listener.TraceCodeListener
import com.vortexdbg.listener.TraceReadListener
import com.vortexdbg.listener.TraceWriteListener
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.MemoryBlock
import com.vortexdbg.memory.MemoryMap
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.thread.Task
import com.vortexdbg.unwind.Frame
import com.vortexdbg.unwind.Unwinder
import com.github.zhkl0228.demumble.DemanglerFactory
import com.github.zhkl0228.demumble.GccDemangler
import keystone.Keystone
import keystone.KeystoneArchitecture
import keystone.KeystoneEncoded
import keystone.KeystoneMode
import org.apache.commons.codec.DecoderException
import org.apache.commons.codec.binary.Hex
import unicorn.Arm64Const
import unicorn.ArmConst
import unicorn.UnicornConst

import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Callable

class McpTools(private val emulator: Emulator<*>, private val server: McpServer) {

    private val customTools: MutableList<CustomTool> = ArrayList()
    private val allocatedBlocks: MutableMap<Long, Allocation> = LinkedHashMap()
    private var activeTraceCode: TraceHook? = null
    private var activeTraceRead: TraceHook? = null
    private var activeTraceWrite: TraceHook? = null

    private class Allocation(@JvmField val block: MemoryBlock, @JvmField val runtime: Boolean, @JvmField val size: Int)

    fun addCustomTool(name: String, description: String, vararg paramNames: String) {
        customTools.add(CustomTool(name, description, arrayOf(*paramNames)))
    }

    fun getToolSchemas(): JSONArray {
        val tools = JSONArray()
        tools.add(toolSchema("check_connection", "Check emulator status: architecture, backend, mode, state, modules. Call first."))
        tools.add(toolSchema("read_memory", "Read memory at address and return hex dump",
                param("address", "string", "Hex address, e.g. 0x40001000"),
                param("size", "integer", "Number of bytes to read, default 0x70")))
        tools.add(toolSchema("write_memory", "Write hex-encoded bytes to memory at address.",
                param("address", "string", "Hex address"),
                param("hex_bytes", "string", "Hex-encoded bytes, e.g. \"48656c6c6f\". Also accepts: data, hex_data, bytes.")))
        tools.add(toolSchema("list_memory_map", "List all memory mapped regions with base, size and permissions"))
        tools.add(toolSchema("search_memory", "Search memory for hex pattern (with ?? wildcards) or text string.",
                param("pattern", "string", "Hex bytes with optional ?? wildcards, or text if type='string'"),
                param("type", "string", "Optional. 'hex' (default) or 'string'"),
                param("module_name", "string", "Optional. Limit search to this module"),
                param("start", "string", "Optional. Hex start address"),
                param("end", "string", "Optional. Hex end address"),
                param("scope", "string", "Optional. 'stack' or 'heap'"),
                param("permission", "string", "Optional. For scope='heap': 'read'/'write'/'execute'. Default 'write'"),
                param("max_results", "integer", "Optional. Default 50")))

        tools.add(toolSchema("get_registers", "Read all general purpose registers"))
        tools.add(toolSchema("get_register", "Read a specific register by name",
                param("name", "string", "Register name, e.g. X0, R0, SP, PC, LR")))
        tools.add(toolSchema("set_register", "Write a value to a specific register",
                param("name", "string", "Register name"),
                param("value", "string", "Hex value to write")))

        tools.add(toolSchema("disassemble", "Disassemble instructions at address. Branch targets auto-annotated with symbol names.",
                param("address", "string", "Hex address"),
                param("count", "integer", "Number of instructions, default 10")))
        tools.add(toolSchema("assemble", "Assemble instruction text to machine code hex (does not write to memory)",
                param("assembly", "string", "Assembly instruction text, e.g. 'mov x0, #1'"),
                param("address", "string", "Hex address for PC-relative encoding, default 0")))
        tools.add(toolSchema("patch", "Assemble instruction and write to memory at address",
                param("address", "string", "Hex address to patch"),
                param("assembly", "string", "Assembly instruction text")))
        tools.add(toolSchema("add_breakpoint", "Add breakpoint at address.",
                param("address", "string", "Hex address"),
                param("temporary", "boolean", "Auto-remove after first hit. Default false.")))
        tools.add(toolSchema("remove_breakpoint", "Remove breakpoint at address.",
                param("address", "string", "Hex address")))
        tools.add(toolSchema("list_breakpoints", "List all breakpoints with address, module info and disassembly."))
        tools.add(toolSchema("add_breakpoint_by_symbol", "Add breakpoint at module symbol (preferred over find_symbol + add_breakpoint).",
                param("module_name", "string", "Module name"),
                param("symbol_name", "string", "Symbol name, e.g. JNI_OnLoad"),
                param("temporary", "boolean", "Auto-remove after first hit. Default false.")))
        tools.add(toolSchema("add_breakpoint_by_offset", "Add breakpoint at module base + offset (for IDA/Ghidra offsets).",
                param("module_name", "string", "Module name"),
                param("offset", "string", "Hex offset, e.g. 0x1234"),
                param("temporary", "boolean", "Auto-remove after first hit. Default false.")))
        tools.add(toolSchema("continue_execution", "Resume execution from current PC."))
        tools.add(toolSchema("step_over", "Step over current instruction (skip function calls)."))
        tools.add(toolSchema("step_into", "Execute N instructions then stop.",
                param("count", "integer", "Number of instructions. Default 1.")))
        tools.add(toolSchema("step_out", "Run until current function returns (bp at LR)."))
        tools.add(toolSchema("next_block", "Break at next basic block start. Unicorn only."))
        tools.add(toolSchema("step_until_mnemonic", "Break when specified mnemonic executes. Unicorn only.",
                param("mnemonic", "string", "e.g. 'bl', 'ret'. Lowercase.")))
        tools.add(toolSchema("poll_events", "Poll runtime events (breakpoint_hit, execution_completed, trace_code/read/write). Call after execution tools.",
                param("timeout_ms", "integer", "Max ms to wait. Default 10000. 0=no wait.")))

        tools.add(toolSchema("trace_read", "Trace memory reads in address range. Events via poll_events. Auto-removed on bp/step/finish.",
                param("begin", "string", "Hex start address"),
                param("end", "string", "Hex end address"),
                param("break_on", "string", "Optional. Hex address to pause on when hit.")))
        tools.add(toolSchema("trace_write", "Trace memory writes in address range. Events via poll_events. Auto-removed on bp/step/finish.",
                param("begin", "string", "Hex start address"),
                param("end", "string", "Hex end address"),
                param("break_on", "string", "Optional. Hex address to pause on when hit.")))
        tools.add(toolSchema("trace_code", "Trace instruction execution in range. Events include regs_read (before) and prev_write (after previous insn) for data flow tracking.",
                param("begin", "string", "Hex start address"),
                param("end", "string", "Hex end address"),
                param("break_on", "string", "Optional. Hex PC to pause on when reached.")))
        tools.add(toolSchema("get_callstack", "Get call stack (backtrace) with PC, module, offset and nearest symbol."))
        tools.add(toolSchema("find_symbol", "Find symbol by name in module, or find nearest symbol to an address.",
                param("module_name", "string", "Optional. Module name"),
                param("symbol_name", "string", "Optional. Symbol name"),
                param("address", "string", "Optional. Hex address to find nearest symbol for")))
        tools.add(toolSchema("read_string", "Read null-terminated C string (UTF-8) from memory.",
                param("address", "string", "Hex address"),
                param("max_length", "integer", "Max bytes. Default 256.")))
        tools.add(toolSchema("read_std_string", "Read C++ std::string (libc++ layout, auto-detects SSO/heap).",
                param("address", "string", "Hex address of std::string object")))
        tools.add(toolSchema("read_pointer", "Read pointer(s) at address, optionally follow chain (isa, vtable, etc).",
                param("address", "string", "Hex address"),
                param("depth", "integer", "Optional. Chain depth. Default 1."),
                param("offset", "integer", "Optional. Byte offset at each dereference. Default 0.")))
        tools.add(toolSchema("read_typed", "Read memory as typed values: int8/16/32/64, uint8/16/32/64, float, double, pointer.",
                param("address", "string", "Hex address"),
                param("type", "string", "Data type"),
                param("count", "integer", "Optional. Number of elements. Default 1.")))
        tools.add(toolSchema("call_function", "Call native function at address. Requires isRunning=false. Arg format in instructions. " +
                        "Traces set before this call remain active. Returns function result with auto pointer preview.",
                param("address", "string", "Hex address of function"),
                argsParam(),
                param("preview_size", "integer", "Optional. Hex preview bytes at return pointer. Default 64. 0=disable.")))

        tools.add(toolSchema("call_symbol", "Call exported function by module+symbol name. Requires isRunning=false. Same arg format as call_function.",
                param("module_name", "string", "Module name, e.g. 'libc.so'"),
                param("symbol_name", "string", "Symbol name, e.g. 'malloc'"),
                argsParam(),
                param("preview_size", "integer", "Optional. Default 64. 0=disable.")))

        tools.add(toolSchema("list_modules", "List loaded modules with name, base and size.",
                param("filter", "string", "Optional. Case-insensitive name filter.")))
        tools.add(toolSchema("get_module_info", "Get module details: base, size, exports count, dependencies.",
                param("module_name", "string", "Module name")))
        tools.add(toolSchema("list_exports", "List exported symbols of a module with addresses and demangled names.",
                param("module_name", "string", "Module name"),
                param("filter", "string", "Optional. Case-insensitive name filter.")))
        tools.add(toolSchema("get_threads", "List all threads/tasks with IDs and status."))
        tools.add(toolSchema("allocate_memory", "Allocate RW memory. See instructions for malloc vs mmap strategy.",
                param("size", "integer", "Bytes to allocate (or inferred from data)"),
                param("data", "string", "Optional. Hex bytes to fill immediately."),
                param("runtime", "boolean", "Optional. true=mmap, false=malloc. Auto-selected based on state.")))
        tools.add(toolSchema("free_memory", "Free memory allocated via allocate_memory.",
                param("address", "string", "Hex address to free")))
        tools.add(toolSchema("list_allocations", "List active allocations from allocate_memory."))


        for (ct in customTools) {
            val schema = JSONObject(true)
            schema.put("name", ct.name)
            schema.put("description", "[Custom] " + ct.description + ". Triggers target function execution (library already loaded). Set breakpoints/traces BEFORE calling, then poll_events for results.")
            schema.put("inputSchema", buildInputSchema(*ct.paramNames))
            tools.add(schema)
        }
        return tools
    }

    fun callTool(name: String, args: JSONObject): JSONObject {
        if (isExecutionTool(name)) {
            return dispatchTool(name, args)
        }
        if (!server.isDebugIdle()) {
            return errorResult("Emulator is not in debug idle state. Tools can only be called when emulator is stopped at a breakpoint.")
        }
        return server.runOnDebuggerThread(Callable { dispatchTool(name, args) })
    }

    private fun isExecutionTool(name: String): Boolean {
        if ("continue_execution" == name) return true
        if ("step_over" == name) return true
        if ("step_into" == name) return true
        if ("step_out" == name) return true
        if ("next_block" == name) return true
        if ("step_until_mnemonic" == name) return true
        if ("poll_events" == name) return true
        if ("check_connection" == name) return true
        for (ct in customTools) {
            if (ct.name == name) return true
        }
        return false
    }

    private fun dispatchTool(name: String, args: JSONObject): JSONObject {
        when (name) {
            "check_connection" -> return checkConnection()
            "read_memory" -> return readMemory(args)
            "write_memory" -> return writeMemory(args)
            "list_memory_map" -> return listMemoryMap()
            "search_memory" -> return searchMemory(args)
            "get_registers" -> return getRegisters()
            "get_register" -> return getRegister(args)
            "set_register" -> return setRegister(args)
            "disassemble" -> return disassemble(args)
            "assemble" -> return assemble(args)
            "patch" -> return patch(args)
            "add_breakpoint" -> return addBreakpoint(args)
            "add_breakpoint_by_symbol" -> return addBreakpointBySymbol(args)
            "add_breakpoint_by_offset" -> return addBreakpointByOffset(args)
            "remove_breakpoint" -> return removeBreakpoint(args)
            "list_breakpoints" -> return listBreakpoints()
            "continue_execution" -> return continueExecution()
            "step_over" -> return stepOver()
            "step_into" -> return stepInto(args)
            "step_out" -> return stepOut()
            "next_block" -> return nextBlock()
            "step_until_mnemonic" -> return stepUntilMnemonic(args)
            "poll_events" -> return pollEvents(args)
            "trace_read" -> return traceRead(args)
            "trace_write" -> return traceWrite(args)
            "trace_code" -> return traceCode(args)
            "get_callstack" -> return getCallstack()
            "find_symbol" -> return findSymbol(args)
            "read_string" -> return readString(args)
            "read_std_string" -> return readStdString(args)
            "read_pointer" -> return readPointer(args)
            "read_typed" -> return readTyped(args)
            "call_function" -> return callFunction(args)
            "call_symbol" -> return callSymbol(args)
            "list_modules" -> return listModules(args)
            "get_module_info" -> return getModuleInfo(args)
            "list_exports" -> return listExports(args)
            "get_threads" -> return getThreads()
            "allocate_memory" -> return allocateMemory(args)
            "free_memory" -> return freeMemory(args)
            "list_allocations" -> return listAllocations()
            else -> {
                for (ct in customTools) {
                    if (ct.name == name) {
                        return executeCustomTool(ct, args)
                    }
                }
                return errorResult("Unknown tool: $name")
            }
        }
    }

    private fun checkConnection(): JSONObject {
        val sb = StringBuilder()
        val family = emulator.getFamily()
        val backendClass = emulator.getBackend().javaClass.simpleName
        val debugger = emulator.attach()
        val hasRunnable = debugger.hasRunnable()
        sb.append(family.name).append(' ').append(if (emulator.is64Bit()) "ARM64" else "ARM32")
                .append(" | ").append(backendClass).append(" (").append(getBackendCapabilities(backendClass)).append(")\n")
        val processName = emulator.getProcessName()
        val lastSlash = processName.lastIndexOf('/')
        sb.append(if (lastSlash >= 0) processName.substring(lastSlash + 1) else processName)
        sb.append(" | ").append(if (hasRunnable) "custom_tools" else "bp_debug")
                .append(" idle=").append(server.isDebugIdle())
                .append(" running=").append(emulator.isRunning())
                .append(" bp=").append(debugger.getBreakPoints().size)
                .append(" events=").append(server.getPendingEventCount()).append('\n')
        val modules = emulator.getMemory().getLoadedModules()
        sb.append(modules.size).append(" modules")
        for (m in modules) {
            val isSystemLib = m.name.startsWith("lib") || m.base > 0x100000000L
            if (!isSystemLib) {
                sb.append(", ").append(m.name).append("@0x").append(java.lang.Long.toHexString(m.base))
            }
        }
        sb.append('\n')
        return textResult(sb.toString())
    }

    private fun readMemory(args: JSONObject): JSONObject {
        val address = parseAddress(args.getString("address"))
        val size = if (args.containsKey("size")) args.getIntValue("size") else 0x70
        try {
            val data = emulator.getBackend().mem_read(address, size.toLong())
            return textResult(hexDump(data, address))
        } catch (e: Exception) {
            return errorResult("Failed to read memory at 0x" + java.lang.Long.toHexString(address) + ": " + exMsg(e))
        }
    }

    private fun writeMemory(args: JSONObject): JSONObject {
        val address = parseAddress(args.getString("address"))
        var hexBytes = args.getString("hex_bytes")
        if (hexBytes == null) hexBytes = args.getString("data")
        if (hexBytes == null) hexBytes = args.getString("hex_data")
        if (hexBytes == null) hexBytes = args.getString("bytes")
        if (hexBytes == null) {
            return errorResult("Missing required parameter. Use 'hex_bytes' (hex encoded string, e.g. \"48656c6c6f\"). " +
                    "Also accepts aliases: 'data', 'hex_data', 'bytes'.")
        }
        try {
            val data = Hex.decodeHex(hexBytes.toCharArray())
            emulator.getBackend().mem_write(address, data)
            return textResult("Written " + data.size + " bytes to 0x" + java.lang.Long.toHexString(address))
        } catch (e: DecoderException) {
            return errorResult("Invalid hex string: \"" + hexBytes + "\". Expected hex-encoded bytes, e.g. \"48656c6c6f\" for \"Hello\".")
        } catch (e: Exception) {
            return errorResult("Failed to write memory at 0x" + java.lang.Long.toHexString(address) + ": " + exMsg(e))
        }
    }

    private fun listMemoryMap(): JSONObject {
        val maps = emulator.getMemory().getMemoryMap()
        val memory = emulator.getMemory()
        val moduleRanges = LinkedHashMap<String, LongArray>()
        val anonymous: MutableList<LongArray> = ArrayList()
        for (map in maps) {
            val module = memory.findModuleByAddress(map.base)
            if (module != null) {
                val range = moduleRanges[module.name]
                val end = map.base + map.size
                if (range == null) {
                    moduleRanges.put(module.name, longArrayOf(map.base, end))
                } else {
                    if (map.base < range[0]) range[0] = map.base
                    if (end > range[1]) range[1] = end
                }
            } else {
                anonymous.add(longArrayOf(map.base, map.base + map.size, map.prot.toLong()))
            }
        }
        val sb = StringBuilder()
        sb.append(moduleRanges.size).append(" modules, ").append(anonymous.size).append(" anonymous:\n")
        for (e in moduleRanges.entries) {
            val r = e.value
            sb.append(e.key).append(" 0x").append(java.lang.Long.toHexString(r[0]))
                    .append("-0x").append(java.lang.Long.toHexString(r[1]))
                    .append(" 0x").append(java.lang.Long.toHexString(r[1] - r[0])).append('\n')
        }
        for (r in anonymous) {
            sb.append(String.format("0x%x-0x%x 0x%x %s\n", r[0], r[1], r[1] - r[0], permString(r[2].toInt())))
        }
        return textResult(sb.toString())
    }

    private fun searchMemory(args: JSONObject): JSONObject {
        val patternStr = args.getString("pattern")
        val type = if (args.containsKey("type")) args.getString("type") else "hex"
        val moduleName = args.getString("module_name")
        val startStr = args.getString("start")
        val endStr = args.getString("end")
        val scope = args.getString("scope")
        val permission = args.getString("permission")
        val maxResults = if (args.containsKey("max_results")) args.getIntValue("max_results") else 50

        val patternBytes: ByteArray
        var maskBytes: ByteArray?
        try {
            if ("string".equals(type, ignoreCase = true)) {
                patternBytes = patternStr.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                maskBytes = null
            } else {
                val hex = patternStr.replace(" ", "")
                if (hex.length % 2 != 0) {
                    return errorResult("Hex pattern must have even number of characters: $patternStr")
                }
                val byteLen = hex.length / 2
                patternBytes = ByteArray(byteLen)
                maskBytes = ByteArray(byteLen)
                var hasMask = false
                for (i in 0 until byteLen) {
                    val byteStr = hex.substring(i * 2, i * 2 + 2)
                    if ("??" == byteStr) {
                        patternBytes[i] = 0
                        maskBytes!![i] = 0
                        hasMask = true
                    } else {
                        patternBytes[i] = Integer.parseInt(byteStr, 16).toByte()
                        maskBytes!![i] = 0xFF.toByte()
                    }
                }
                if (!hasMask) {
                    maskBytes = null
                }
            }
        } catch (e: NumberFormatException) {
            return errorResult("Invalid hex pattern: $patternStr")
        }

        val ranges: MutableList<LongArray> = ArrayList()
        if ("stack".equals(scope, ignoreCase = true)) {
            val sp = emulator.getContext<RegisterContext>().getStackPointer()
            val stackBase = emulator.getMemory().getStackBase()
            ranges.add(longArrayOf(sp.peer, stackBase))
        } else if ("heap".equals(scope, ignoreCase = true)) {
            val prot = resolvePermission(permission)
            for (map in emulator.getMemory().getMemoryMap()) {
                if ((map.prot and prot) != 0) {
                    ranges.add(longArrayOf(map.base, map.base + map.size))
                }
            }
        } else if (moduleName != null && !moduleName.isEmpty()) {
            val module = emulator.getMemory().findModule(moduleName)
            if (module == null) {
                return errorResult("Module not found: $moduleName")
            }
            ranges.add(longArrayOf(module.base, module.base + module.size))
        } else if (startStr != null && endStr != null) {
            ranges.add(longArrayOf(parseAddress(startStr), parseAddress(endStr)))
        } else {
            for (map in emulator.getMemory().getMemoryMap()) {
                if ((map.prot and 1) != 0) {
                    ranges.add(longArrayOf(map.base, map.base + map.size))
                }
            }
        }

        val backend = emulator.getBackend()
        val memory = emulator.getMemory()
        val results: MutableList<String> = ArrayList()
        val chunkSize = 0x10000

        for (range in ranges) {
            val rangeStart = range[0]
            val rangeEnd = range[1]
            val overlap = (patternBytes.size - 1).toLong()
            val step = Math.max(1, chunkSize - overlap)

            var addr = rangeStart
            while (addr < rangeEnd && results.size < maxResults) {
                val readSize = Math.min(chunkSize.toLong(), rangeEnd - addr).toInt()
                val chunk: ByteArray
                try {
                    chunk = backend.mem_read(addr, readSize.toLong())
                } catch (e: Exception) {
                    addr += step
                    continue
                }
                var i = 0
                while (i <= chunk.size - patternBytes.size && results.size < maxResults) {
                    if (matchPattern(chunk, i, patternBytes, maskBytes)) {
                        val matchAddr = addr + i
                        val sb = StringBuilder()
                        sb.append("0x").append(java.lang.Long.toHexString(matchAddr))
                        val module = memory.findModuleByAddress(matchAddr)
                        if (module != null) {
                            sb.append("  (").append(module.name).append("+0x").append(java.lang.Long.toHexString(matchAddr - module.base)).append(')')
                        }
                        results.add(sb.toString())
                    }
                    i++
                }
                addr += step
            }
            if (results.size >= maxResults) break
        }

        if (results.isEmpty()) {
            return textResult("Pattern not found.")
        }
        val sb = StringBuilder()
        sb.append("Found ").append(results.size).append(" match(es)")
        if (results.size >= maxResults) {
            sb.append(" (limit reached)")
        }
        sb.append(":\n")
        for (r in results) {
            sb.append(r).append('\n')
        }
        return textResult(sb.toString())
    }

    private fun getRegisters(): JSONObject {
        val backend = emulator.getBackend()
        val sb = StringBuilder()
        if (emulator.is64Bit()) {
            val zeros: MutableList<String> = ArrayList()
            for (i in 0..28) {
                val value = backend.reg_read(Arm64Const.UC_ARM64_REG_X0 + i).toLong()
                if (value == 0L) {
                    zeros.add("X$i")
                } else {
                    sb.append("X").append(i).append("=0x").append(java.lang.Long.toHexString(value)).append('\n')
                }
            }
            sb.append("FP=0x").append(java.lang.Long.toHexString(backend.reg_read(Arm64Const.UC_ARM64_REG_FP).toLong())).append('\n')
            sb.append("LR=0x").append(java.lang.Long.toHexString(backend.reg_read(Arm64Const.UC_ARM64_REG_LR).toLong())).append('\n')
            sb.append("SP=0x").append(java.lang.Long.toHexString(backend.reg_read(Arm64Const.UC_ARM64_REG_SP).toLong())).append('\n')
            sb.append("PC=0x").append(java.lang.Long.toHexString(backend.reg_read(Arm64Const.UC_ARM64_REG_PC).toLong())).append('\n')
            if (!zeros.isEmpty()) {
                sb.append("Zero: ").append(java.lang.String.join(",", zeros)).append('\n')
            }
        } else {
            val zeros: MutableList<String> = ArrayList()
            for (i in 0..12) {
                val value = backend.reg_read(ArmConst.UC_ARM_REG_R0 + i).toInt().toLong() and 0xffffffffL
                if (value == 0L) {
                    zeros.add("R$i")
                } else {
                    sb.append("R").append(i).append("=0x").append(java.lang.Long.toHexString(value)).append('\n')
                }
            }
            sb.append("SP=0x").append(java.lang.Long.toHexString(backend.reg_read(ArmConst.UC_ARM_REG_SP).toInt().toLong() and 0xffffffffL)).append('\n')
            sb.append("LR=0x").append(java.lang.Long.toHexString(backend.reg_read(ArmConst.UC_ARM_REG_LR).toInt().toLong() and 0xffffffffL)).append('\n')
            sb.append("PC=0x").append(java.lang.Long.toHexString(backend.reg_read(ArmConst.UC_ARM_REG_PC).toInt().toLong() and 0xffffffffL)).append('\n')
            if (!zeros.isEmpty()) {
                sb.append("Zero: ").append(java.lang.String.join(",", zeros)).append('\n')
            }
        }
        return textResult(sb.toString())
    }

    private fun getRegister(args: JSONObject): JSONObject {
        val raw = args.getString("name")
        if (raw == null || raw.isEmpty()) {
            return errorResult("Missing required parameter 'name'. Specify a register name, e.g. X0, SP, PC.")
        }
        val name = raw.uppercase()
        try {
            val regId = resolveRegister(name)
            val backend = emulator.getBackend()
            if (emulator.is64Bit()) {
                var value = backend.reg_read(regId).toLong()
                if (name.startsWith("W")) {
                    value = value and 0xFFFFFFFFL
                }
                return textResult(name + " = 0x" + java.lang.Long.toHexString(value))
            } else {
                val value = backend.reg_read(regId).toInt().toLong() and 0xffffffffL
                return textResult(name + " = 0x" + java.lang.Long.toHexString(value))
            }
        } catch (e: Exception) {
            return errorResult("Failed to read register $name: " + exMsg(e))
        }
    }

    private fun setRegister(args: JSONObject): JSONObject {
        val raw = args.getString("name")
        if (raw == null || raw.isEmpty()) {
            return errorResult("Missing required parameter 'name'. Specify a register name, e.g. X0, SP, PC.")
        }
        val name = raw.uppercase()
        val value = parseAddress(args.getString("value"))
        try {
            val regId = resolveRegister(name)
            emulator.getBackend().reg_write(regId, value)
            return textResult(name + " set to 0x" + java.lang.Long.toHexString(value))
        } catch (e: Exception) {
            return errorResult("Failed to set register $name: " + exMsg(e))
        }
    }

    private fun disassemble(args: JSONObject): JSONObject {
        val address = parseAddress(args.getString("address"))
        val count = if (args.containsKey("count")) args.getIntValue("count") else 10
        try {
            val size = count * 4
            val code = emulator.getBackend().mem_read(address, size.toLong())
            val thumb = emulator.is32Bit() && ARM.isThumb(emulator.getBackend())
            val insns = emulator.disassemble(address, code, thumb, count.toLong())
            val memory = emulator.getMemory()
            val demangler = DemanglerFactory.createDemangler()
            val sb = StringBuilder()
            for (insn in insns) {
                sb.append(String.format("0x%x: %s %s", insn.address, insn.mnemonic, insn.opStr))
                val annotation = resolveInsnTargetSymbol(insn, memory, demangler)
                if (annotation != null) {
                    sb.append("  ; ").append(annotation)
                }
                sb.append('\n')
            }
            if (insns.size == 0) {
                sb.append("No instructions at 0x").append(java.lang.Long.toHexString(address))
            }
            return textResult(sb.toString())
        } catch (e: Exception) {
            return errorResult("Disassemble failed: " + exMsg(e))
        }
    }

    private fun resolveInsnTargetSymbol(insn: Instruction, memory: Memory, demangler: GccDemangler): String? {
        val mnemonic = insn.mnemonic.lowercase()
        if (!isBranchMnemonic(mnemonic)) {
            return null
        }
        val m = IMM_ADDR_PATTERN.matcher(insn.opStr)
        var target: Long = -1
        while (m.find()) {
            try {
                target = java.lang.Long.parseUnsignedLong(m.group(1), 16)
            } catch (ignored: NumberFormatException) {
            }
        }
        if (target <= 0) {
            return null
        }
        val module = memory.findModuleByAddress(target)
        if (module == null) {
            return null
        }
        val symbol = module.findClosestSymbolByAddress(target, false)
        if (symbol != null && target - symbol.getAddress() <= Unwinder.SYMBOL_SIZE) {
            val name = demangler.demangle(symbol.getName())
            val offset = target - symbol.getAddress()
            if (offset == 0L) {
                return name
            }
            return name + "+0x" + java.lang.Long.toHexString(offset)
        }
        return module.name + "+0x" + java.lang.Long.toHexString(target - module.base)
    }

    private fun assemble(args: JSONObject): JSONObject {
        val assembly = args.getString("assembly")
        try {
            createKeystone().use { keystone ->
                val encoded = keystone.assemble(assembly)
                val code = encoded.machineCode
                return textResult("Machine code: " + Hex.encodeHexString(code) + " (" + code.size + " bytes)")
            }
        } catch (e: Exception) {
            return errorResult("Assemble failed: " + exMsg(e))
        }
    }

    private fun patch(args: JSONObject): JSONObject {
        val address = parseAddress(args.getString("address"))
        val assembly = args.getString("assembly")
        try {
            createKeystone().use { keystone ->
                val encoded = keystone.assemble(assembly)
                val code = encoded.machineCode
                emulator.getBackend().mem_write(address, code)
                return textResult("Patched " + code.size + " bytes at 0x" + java.lang.Long.toHexString(address) +
                        ": " + Hex.encodeHexString(code))
            }
        } catch (e: Exception) {
            return errorResult("Patch failed: " + exMsg(e))
        }
    }

    private fun addBreakpoint(args: JSONObject): JSONObject {
        val address = parseAddress(args.getString("address"))
        val temporary = args.containsKey("temporary") && args.getBooleanValue("temporary")
        try {
            val bp = emulator.attach().addBreakPoint(address)
            if (temporary) {
                bp.setTemporary(true)
            }
            val type = if (temporary) "Temporary breakpoint" else "Breakpoint"
            return textResult(type + " added at 0x" + java.lang.Long.toHexString(address))
        } catch (e: Exception) {
            return errorResult("Failed to add breakpoint: " + exMsg(e))
        }
    }

    private fun addBreakpointBySymbol(args: JSONObject): JSONObject {
        val moduleName = args.getString("module_name")
        val symbolName = args.getString("symbol_name")
        val temporary = args.containsKey("temporary") && args.getBooleanValue("temporary")
        try {
            val module = emulator.getMemory().findModule(moduleName)
            if (module == null) {
                return errorResult("Module not found: $moduleName")
            }
            val debugger = emulator.attach()
            val bp = debugger.addBreakPoint(module, symbolName)
            if (bp == null) {
                return errorResult("Symbol '" + symbolName + "' not found in " + moduleName)
            }
            if (temporary) {
                bp.setTemporary(true)
            }
            var addr: Long = 0
            for (entry in debugger.getBreakPoints().entries) {
                if (entry.value === bp) {
                    addr = entry.key
                    break
                }
            }
            val typeStr = if (temporary) "Temporary breakpoint" else "Breakpoint"
            return textResult(typeStr + " added at " + symbolName + " (0x" + java.lang.Long.toHexString(addr) +
                    ", " + moduleName + "+0x" + java.lang.Long.toHexString(addr - module.base) + ")")
        } catch (e: Exception) {
            return errorResult("Failed to add breakpoint by symbol: " + exMsg(e))
        }
    }

    private fun addBreakpointByOffset(args: JSONObject): JSONObject {
        val moduleName = args.getString("module_name")
        val offset = parseAddress(args.getString("offset"))
        val temporary = args.containsKey("temporary") && args.getBooleanValue("temporary")
        try {
            val module = emulator.getMemory().findModule(moduleName)
            if (module == null) {
                return errorResult("Module not found: $moduleName")
            }
            val bp = emulator.attach().addBreakPoint(module, offset)
            if (temporary) {
                bp.setTemporary(true)
            }
            val addr = module.base + offset
            val typeStr = if (temporary) "Temporary breakpoint" else "Breakpoint"
            return textResult(typeStr + " added at " + moduleName + "+0x" + java.lang.Long.toHexString(offset) +
                    " (0x" + java.lang.Long.toHexString(addr) + ")")
        } catch (e: Exception) {
            return errorResult("Failed to add breakpoint by offset: " + exMsg(e))
        }
    }

    private fun listBreakpoints(): JSONObject {
        try {
            val breakPoints = emulator.attach().getBreakPoints()
            if (breakPoints.isEmpty()) {
                return textResult("No breakpoints set.")
            }
            val memory = emulator.getMemory()
            val backend = emulator.getBackend()
            val sb = StringBuilder()
            sb.append(String.format("Total: %d breakpoint(s)%n", breakPoints.size))
            for (entry in breakPoints.entries) {
                val addr = entry.key
                val bp = entry.value
                val module = memory.findModuleByAddress(addr)
                val location: String
                if (module != null) {
                    val offset = addr - module.base
                    location = String.format("%s+0x%x", module.name, offset)
                } else {
                    location = "unknown"
                }
                val temp = if (bp.isTemporary()) " [temporary]" else ""
                sb.append(String.format("0x%x  %s%s", addr, location, temp))
                try {
                    val code = backend.mem_read(addr, 4)
                    val thumb = emulator.is32Bit() && (addr and 1L) != 0L
                    val disAddr = if (thumb) (addr and 1L.inv()) else addr
                    val insns = emulator.disassemble(disAddr, code, thumb, 1L)
                    if (insns.size > 0) {
                        sb.append(String.format("  ; %s %s", insns[0].mnemonic, insns[0].opStr))
                    }
                } catch (ignored: Exception) {
                }
                sb.append('\n')
            }
            return textResult(sb.toString())
        } catch (e: Exception) {
            return errorResult("Failed to list breakpoints: " + exMsg(e))
        }
    }

    private fun removeBreakpoint(args: JSONObject): JSONObject {
        val address = parseAddress(args.getString("address"))
        try {
            val removed = emulator.attach().removeBreakPoint(address)
            if (removed) {
                return textResult("Breakpoint removed at 0x" + java.lang.Long.toHexString(address))
            } else {
                return errorResult("No breakpoint found at 0x" + java.lang.Long.toHexString(address))
            }
        } catch (e: Exception) {
            return errorResult("Failed to remove breakpoint: " + exMsg(e))
        }
    }

    private fun continueExecution(): JSONObject {
        server.injectCommand("c")
        return textResult("Resumed.")
    }

    private fun stepOver(): JSONObject {
        server.injectCommand("n")
        return textResult("Stepping over.")
    }

    private fun stepInto(args: JSONObject): JSONObject {
        val count = if (args.containsKey("count")) args.getIntValue("count") else 1
        if (count <= 1) {
            server.injectCommand("s")
        } else {
            server.injectCommand("s$count")
        }
        return textResult("Stepping $count insn.")
    }

    private fun stepOut(): JSONObject {
        if (!server.isDebugIdle()) {
            return errorResult("Emulator is not in debug idle state.")
        }
        try {
            val result = server.runOnDebuggerThread(Callable {
                val backend = emulator.getBackend()
                val lrReg = if (emulator.is64Bit()) Arm64Const.UC_ARM64_REG_LR else ArmConst.UC_ARM_REG_LR
                var lr = backend.reg_read(lrReg).toLong()
                if (emulator.is32Bit()) {
                    lr = lr and 0xffffffffL
                }
                val bp = emulator.attach().addBreakPoint(lr)
                bp.setTemporary(true)
                textResult("Temporary breakpoint set at LR=0x" + java.lang.Long.toHexString(lr))
            })
            if (result.containsKey("isError")) {
                return result
            }
            server.injectCommand("c")
            val text = result.getJSONArray("content").getJSONObject(0).getString("text")
            return textResult(text + "\nResumed, will break at LR.")
        } catch (e: Exception) {
            return errorResult("Step out failed: " + exMsg(e))
        }
    }

    private fun nextBlock(): JSONObject {
        if (!server.isDebugIdle()) {
            return errorResult("Emulator is not in debug idle state.")
        }
        val backendClass = emulator.getBackend().javaClass.simpleName
        if (backendClass.contains("Hypervisor") || backendClass.contains("Dynarmic") || backendClass.contains("Kvm")) {
            return errorResult("next_block is not supported on " + backendClass + " backend. Only Unicorn/Unicorn2 backends support BlockHook.")
        }
        server.injectCommand("nb")
        return textResult("Resuming, break at next block.")
    }

    private fun stepUntilMnemonic(args: JSONObject): JSONObject {
        val mnemonic = args.getString("mnemonic")
        if (mnemonic == null || mnemonic.isEmpty()) {
            return errorResult("mnemonic parameter is required.")
        }
        if (!server.isDebugIdle()) {
            return errorResult("Emulator is not in debug idle state.")
        }
        val backendClass = emulator.getBackend().javaClass.simpleName
        if (backendClass.contains("Hypervisor") || backendClass.contains("Dynarmic") || backendClass.contains("Kvm")) {
            return errorResult("step_until_mnemonic is not supported on " + backendClass +
                    " backend. Only Unicorn/Unicorn2 backends support per-instruction hook (setFastDebug).")
        }
        server.injectCommand("s$mnemonic")
        return textResult("Resuming, break on '$mnemonic'.")
    }

    private fun pollEvents(args: JSONObject): JSONObject {
        val timeoutMs = if (args.containsKey("timeout_ms")) args.getLongValue("timeout_ms") else 10000
        val events = server.pollEvents(timeoutMs)
        if (events.isEmpty()) {
            return textResult("No events received within timeout.")
        }
        val sb = StringBuilder()
        sb.append(String.format("%d event(s):%n", events.size))
        for (event in events) {
            sb.append(event.toJSONString()).append('\n')
        }
        return textResult(sb.toString())
    }

    private fun traceRead(args: JSONObject): JSONObject {
        val begin = parseAddress(args.getString("begin"))
        val end = parseAddress(args.getString("end"))
        val breakOnStr = args.getString("break_on")
        val breakOn = if (breakOnStr != null) parseAddress(breakOnStr) else -1
        try {
            if (activeTraceRead != null) {
                activeTraceRead!!.stopTrace()
                activeTraceRead = null
            }
            activeTraceRead = emulator.traceRead(begin, end, object : TraceReadListener {
                override fun onRead(emu: Emulator<*>, address: Long, data: ByteArray, hex: String): Boolean {
                    val event = JSONObject(true)
                    event.put("event", "trace_read")
                    event.put("pc", "0x" + java.lang.Long.toHexString(emu.getBackend().reg_read(
                            if (emu.is64Bit()) Arm64Const.UC_ARM64_REG_PC else ArmConst.UC_ARM_REG_PC).toLong()))
                    event.put("address", "0x" + java.lang.Long.toHexString(address))
                    event.put("size", data.size)
                    event.put("hex", hex)
                    putModuleInfo(event, emu, address)
                    server.queueEvent(event)
                    if (breakOn != -1L && address == breakOn) {
                        emu.getBackend().setSingleStep(1)
                    }
                    return false
                }
            })
            val msg = StringBuilder("Trace read started: 0x" + java.lang.Long.toHexString(begin) + " - 0x" + java.lang.Long.toHexString(end))
            if (breakOn != -1L) {
                msg.append(", will break on address 0x").append(java.lang.Long.toHexString(breakOn))
            }
            msg.append(".")
            return textResult(msg.toString())
        } catch (e: Exception) {
            return errorResult("Failed to start trace read: " + e.javaClass.name + ": " + exMsg(e))
        }
    }

    private fun traceWrite(args: JSONObject): JSONObject {
        val begin = parseAddress(args.getString("begin"))
        val end = parseAddress(args.getString("end"))
        val breakOnStr = args.getString("break_on")
        val breakOn = if (breakOnStr != null) parseAddress(breakOnStr) else -1
        try {
            if (activeTraceWrite != null) {
                activeTraceWrite!!.stopTrace()
                activeTraceWrite = null
            }
            activeTraceWrite = emulator.traceWrite(begin, end, object : TraceWriteListener {
                override fun onWrite(emu: Emulator<*>, address: Long, size: Int, value: Long): Boolean {
                    val event = JSONObject(true)
                    event.put("event", "trace_write")
                    event.put("pc", "0x" + java.lang.Long.toHexString(emu.getBackend().reg_read(
                            if (emu.is64Bit()) Arm64Const.UC_ARM64_REG_PC else ArmConst.UC_ARM_REG_PC).toLong()))
                    event.put("address", "0x" + java.lang.Long.toHexString(address))
                    event.put("size", size)
                    event.put("value", "0x" + java.lang.Long.toHexString(value))
                    putModuleInfo(event, emu, address)
                    server.queueEvent(event)
                    if (breakOn != -1L && address == breakOn) {
                        emu.getBackend().setSingleStep(1)
                    }
                    return false
                }
            })
            val msg = StringBuilder("Trace write started: 0x" + java.lang.Long.toHexString(begin) + " - 0x" + java.lang.Long.toHexString(end))
            if (breakOn != -1L) {
                msg.append(", will break on address 0x").append(java.lang.Long.toHexString(breakOn))
            }
            msg.append(".")
            return textResult(msg.toString())
        } catch (e: Exception) {
            return errorResult("Failed to start trace write: " + e.javaClass.name + ": " + exMsg(e))
        }
    }

    private var lastTraceWriteRegs: ShortArray? = null
    private var lastTraceInsn: Instruction? = null

    private fun formatRegValues(insn: Instruction, regs: ShortArray?): String? {
        if (regs == null || regs.size == 0) return null
        val backend = emulator.getBackend()
        val sb = StringBuilder()
        for (reg in regs) {
            val regId = insn.mapToUnicornReg(reg.toInt())
            if (emulator.is32Bit()) {
                if ((regId >= ArmConst.UC_ARM_REG_R0 && regId <= ArmConst.UC_ARM_REG_R12) ||
                        regId == ArmConst.UC_ARM_REG_LR || regId == ArmConst.UC_ARM_REG_SP ||
                        regId == ArmConst.UC_ARM_REG_CPSR) {
                    if (sb.length > 0) sb.append(", ")
                    if (regId == ArmConst.UC_ARM_REG_CPSR) {
                        val cpsr = Cpsr.getArm(backend)
                        sb.append(String.format(Locale.US, "cpsr: N=%d, Z=%d, C=%d, V=%d",
                                if (cpsr.isNegative()) 1 else 0, if (cpsr.isZero()) 1 else 0,
                                if (cpsr.hasCarry()) 1 else 0, if (cpsr.isOverflow()) 1 else 0))
                    } else {
                        val value = backend.reg_read(regId).toInt()
                        sb.append(insn.regName(reg.toInt())).append("=0x").append(java.lang.Long.toHexString(value.toLong() and 0xffffffffL))
                    }
                }
            } else {
                if ((regId >= Arm64Const.UC_ARM64_REG_X0 && regId <= Arm64Const.UC_ARM64_REG_X28) ||
                        (regId >= Arm64Const.UC_ARM64_REG_X29 && regId <= Arm64Const.UC_ARM64_REG_SP)) {
                    if (sb.length > 0) sb.append(", ")
                    if (regId == Arm64Const.UC_ARM64_REG_NZCV) {
                        val cpsr = Cpsr.getArm64(backend)
                        if (cpsr.isA32()) {
                            sb.append(String.format(Locale.US, "cpsr: N=%d, Z=%d, C=%d, V=%d",
                                    if (cpsr.isNegative()) 1 else 0, if (cpsr.isZero()) 1 else 0,
                                    if (cpsr.hasCarry()) 1 else 0, if (cpsr.isOverflow()) 1 else 0))
                        } else {
                            sb.append(String.format(Locale.US, "nzcv: N=%d, Z=%d, C=%d, V=%d",
                                    if (cpsr.isNegative()) 1 else 0, if (cpsr.isZero()) 1 else 0,
                                    if (cpsr.hasCarry()) 1 else 0, if (cpsr.isOverflow()) 1 else 0))
                        }
                    } else {
                        val value = backend.reg_read(regId).toLong()
                        sb.append(insn.regName(reg.toInt())).append("=0x").append(java.lang.Long.toHexString(value))
                    }
                } else if (regId >= Arm64Const.UC_ARM64_REG_W0 && regId <= Arm64Const.UC_ARM64_REG_W30) {
                    if (sb.length > 0) sb.append(", ")
                    val value = backend.reg_read(regId).toInt()
                    sb.append(insn.regName(reg.toInt())).append("=0x").append(java.lang.Long.toHexString(value.toLong() and 0xffffffffL))
                }
            }
        }
        return if (sb.length > 0) sb.toString() else null
    }

    private fun traceCode(args: JSONObject): JSONObject {
        val begin = parseAddress(args.getString("begin"))
        val end = parseAddress(args.getString("end"))
        val breakOnStr = args.getString("break_on")
        val breakOn = if (breakOnStr != null) parseAddress(breakOnStr) else -1
        try {
            if (activeTraceCode != null) {
                activeTraceCode!!.stopTrace()
                activeTraceCode = null
            }
            lastTraceWriteRegs = null
            lastTraceInsn = null
            activeTraceCode = emulator.traceCode(begin, end, object : TraceCodeListener {
                override fun onInstruction(emu: Emulator<*>, address: Long, insn: Instruction) {
                    val event = JSONObject(true)
                    event.put("event", "trace_code")
                    event.put("address", "0x" + java.lang.Long.toHexString(address))
                    if (insn != null) {
                        event.put("mnemonic", insn.mnemonic)
                        event.put("operands", insn.opStr)
                        event.put("size", insn.size)
                    }
                    val module = emu.getMemory().findModuleByAddress(address)
                    if (module != null) {
                        event.put("module", module.name)
                        event.put("offset", "0x" + java.lang.Long.toHexString(address - module.base))
                    }
                    if (lastTraceWriteRegs != null && lastTraceInsn != null) {
                        val writeValues = formatRegValues(lastTraceInsn!!, lastTraceWriteRegs)
                        if (writeValues != null) {
                            event.put("prev_write", writeValues)
                        }
                    }
                    if (insn != null) {
                        val regsAccess = insn.regsAccess()
                        if (regsAccess != null) {
                            val readValues = formatRegValues(insn, regsAccess.getRegsRead())
                            if (readValues != null) {
                                event.put("regs_read", readValues)
                            }
                            val regsWrite = regsAccess.getRegsWrite()
                            if (regsWrite != null && regsWrite.size > 0) {
                                lastTraceWriteRegs = regsWrite
                                lastTraceInsn = insn
                            } else {
                                lastTraceWriteRegs = null
                                lastTraceInsn = null
                            }
                        } else {
                            lastTraceWriteRegs = null
                            lastTraceInsn = null
                        }
                    }
                    server.queueEvent(event)
                    if (breakOn != -1L && address == breakOn) {
                        emu.attach().debug("trace_code break_on address hit: 0x" + java.lang.Long.toHexString(address))
                    }
                }
            })
            val msg = StringBuilder("Trace code started: 0x" + java.lang.Long.toHexString(begin) + " - 0x" + java.lang.Long.toHexString(end))
            if (breakOn != -1L) {
                msg.append(", will break on PC 0x").append(java.lang.Long.toHexString(breakOn))
            }
            msg.append(".")
            return textResult(msg.toString())
        } catch (e: Exception) {
            return errorResult("Failed to start trace code: " + e.javaClass.name + ": " + exMsg(e))
        }
    }

    private fun getCallstack(): JSONObject {
        try {
            val unwinder = emulator.getUnwinder()
            val memory = emulator.getMemory()
            val frames = unwinder.getFrames(50)
            if (frames.isEmpty()) {
                return textResult("No call stack frames available.")
            }
            val sb = StringBuilder()
            val demangler = DemanglerFactory.createDemangler()
            for (i in frames.indices) {
                val pc = frames[i].ip!!.peer
                val module = memory.findModuleByAddress(pc)
                sb.append(String.format("#%-3d 0x%x", i, pc))
                if (module != null) {
                    sb.append(String.format("  %s+0x%x", module.name, pc - module.base))
                    val symbol = module.findClosestSymbolByAddress(pc, false)
                    if (symbol != null && pc - symbol.getAddress() <= Unwinder.SYMBOL_SIZE) {
                        sb.append(String.format("  (%s+0x%x)", demangler.demangle(symbol.getName()), pc - symbol.getAddress()))
                    }
                }
                sb.append('\n')
            }
            return textResult(sb.toString())
        } catch (e: Exception) {
            return errorResult("Failed to get callstack: " + e.javaClass.name + ": " + exMsg(e))
        }
    }

    private fun findSymbol(args: JSONObject): JSONObject {
        val moduleName = args.getString("module_name")
        val symbolName = args.getString("symbol_name")
        val addressStr = args.getString("address")
        try {
            if (addressStr != null && !addressStr.isEmpty()) {
                val address = parseAddress(addressStr)
                val module = emulator.getMemory().findModuleByAddress(address)
                if (module == null) {
                    return errorResult("No module found at address 0x" + java.lang.Long.toHexString(address))
                }
                val symbol = module.findClosestSymbolByAddress(address, false)
                if (symbol == null || address - symbol.getAddress() > Unwinder.SYMBOL_SIZE) {
                    return textResult("No symbol found near 0x" + java.lang.Long.toHexString(address) +
                            " (in " + module.name + "+0x" + java.lang.Long.toHexString(address - module.base) + ")")
                }
                val demangler = DemanglerFactory.createDemangler()
                return textResult("0x" + java.lang.Long.toHexString(address) + " = " +
                        module.name + "!" + demangler.demangle(symbol.getName()) +
                        "+0x" + java.lang.Long.toHexString(address - symbol.getAddress()))
            }
            if (moduleName != null && symbolName != null) {
                val module = emulator.getMemory().findModule(moduleName)
                if (module == null) {
                    return errorResult("Module not found: $moduleName")
                }
                val symbol = module.findSymbolByName(symbolName, false)
                if (symbol == null) {
                    return errorResult("Symbol '" + symbolName + "' not found in " + moduleName)
                }
                val demangler = DemanglerFactory.createDemangler()
                return textResult(demangler.demangle(symbol.getName()) +
                        " @ 0x" + java.lang.Long.toHexString(symbol.getAddress()) +
                        " (" + moduleName + "+0x" + java.lang.Long.toHexString(symbol.getAddress() - module.base) + ")")
            }
            return errorResult("Provide either (module_name + symbol_name) or (address).")
        } catch (e: Exception) {
            return errorResult("Find symbol failed: " + e.javaClass.name + ": " + exMsg(e))
        }
    }

    private fun readString(args: JSONObject): JSONObject {
        val address = parseAddress(args.getString("address"))
        val maxLength = if (args.containsKey("max_length")) args.getIntValue("max_length") else 256
        try {
            val data = emulator.getBackend().mem_read(address, maxLength.toLong())
            var len = 0
            while (len < data.size && data[len].toInt() != 0) {
                len++
            }
            val str = String(data, 0, len, java.nio.charset.StandardCharsets.UTF_8)
            var result = "\"" + str + "\" (" + len + "B)"
            if (len == maxLength) {
                result += " [truncated]"
            }
            return textResult(result)
        } catch (e: Exception) {
            return errorResult("Failed to read string at 0x" + java.lang.Long.toHexString(address) + ": " + exMsg(e))
        }
    }

    private fun readStdString(args: JSONObject): JSONObject {
        val address = parseAddress(args.getString("address"))
        try {
            val pointer = VortexdbgPointer.pointer(emulator, address)
            if (pointer == null) {
                return errorResult("Null pointer for address 0x" + java.lang.Long.toHexString(address))
            }
            val stdStr =
                    com.vortexdbg.unix.struct.StdString.createStdString(emulator, pointer)
            val dataSize = stdStr.getDataSize()
            val isTiny = (emulator.getBackend().mem_read(address, 1)[0].toInt() and 1) == 0
            val data = stdStr.getData(emulator)
            val str = String(data, java.nio.charset.StandardCharsets.UTF_8)

            val result = "\"" + str + "\" (" + dataSize + "B, " + (if (isTiny) "SSO" else "heap") + ")"
            return textResult(result)
        } catch (e: Exception) {
            return errorResult("Failed to read std::string at 0x" + java.lang.Long.toHexString(address) + ": " + exMsg(e))
        }
    }

    private fun appendModuleAndSymbol(sb: StringBuilder, memory: Memory, demangler: GccDemangler, address: Long) {
        val module = memory.findModuleByAddress(address)
        if (module != null) {
            sb.append(String.format("  (%s+0x%x)", module.name, address - module.base))
            val symbol = module.findClosestSymbolByAddress(address, false)
            if (symbol != null && address - symbol.getAddress() <= Unwinder.SYMBOL_SIZE) {
                sb.append(String.format("  <%s>", demangler.demangle(symbol.getName())))
            }
        }
    }

    private fun readPointer(args: JSONObject): JSONObject {
        val address = parseAddress(args.getString("address"))
        val depth = if (args.containsKey("depth")) args.getIntValue("depth") else 1
        val offset = if (args.containsKey("offset")) args.getIntValue("offset") else 0
        val is64 = emulator.is64Bit()
        val ptrSize = if (is64) 8 else 4
        val backend = emulator.getBackend()
        val memory = emulator.getMemory()
        val demangler = DemanglerFactory.createDemangler()

        val sb = StringBuilder()
        var currentAddr = address
        try {
            for (level in 0..depth) {
                sb.append(String.format("[%d] 0x%x", level, currentAddr))
                appendModuleAndSymbol(sb, memory, demangler, currentAddr)
                sb.append('\n')

                if (level < depth) {
                    val readAddr = currentAddr + offset
                    val data = backend.mem_read(readAddr, ptrSize.toLong())
                    var ptrValue: Long
                    ptrValue = 0
                    if (is64) {
                        for (i in 7 downTo 0) {
                            ptrValue = (ptrValue shl 8) or (data[i].toLong() and 0xFFL)
                        }
                    } else {
                        for (i in 3 downTo 0) {
                            ptrValue = (ptrValue shl 8) or (data[i].toLong() and 0xFFL)
                        }
                    }
                    if (offset != 0) {
                        sb.append(String.format("    -> read at 0x%x+0x%x = 0x%x%n", currentAddr, offset, ptrValue))
                    } else {
                        sb.append(String.format("    -> 0x%x%n", ptrValue))
                    }
                    if (ptrValue == 0L) {
                        sb.append("    (null pointer, chain ends)\n")
                        break
                    }
                    currentAddr = ptrValue
                }
            }
        } catch (e: Exception) {
            sb.append(String.format("    (read failed at 0x%x: %s)%n", currentAddr, exMsg(e)))
        }
        return textResult(sb.toString())
    }

    private fun readTyped(args: JSONObject): JSONObject {
        val address = parseAddress(args.getString("address"))
        val rawType = args.getString("type")
        if (rawType == null || rawType.isEmpty()) {
            return errorResult("Missing required parameter 'type'. Supported: int8, uint8, int16, uint16, int32, uint32, int64, uint64, float, double, pointer")
        }
        val type = rawType.lowercase()
        val count = if (args.containsKey("count")) args.getIntValue("count") else 1

        val elemSize: Int
        when (type) {
            "int8", "uint8" -> elemSize = 1
            "int16", "uint16" -> elemSize = 2
            "int32", "uint32", "float" -> elemSize = 4
            "int64", "uint64", "double" -> elemSize = 8
            "pointer" -> elemSize = if (emulator.is64Bit()) 8 else 4
            else -> return errorResult("Unsupported type: " + type + ". Supported: int8, uint8, int16, uint16, int32, uint32, int64, uint64, float, double, pointer")
        }

        try {
            val data = emulator.getBackend().mem_read(address, elemSize.toLong() * count)
            val buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val memory = emulator.getMemory()
            val demangler = DemanglerFactory.createDemangler()
            val sb = StringBuilder()

            for (i in 0 until count) {
                val elemAddr = address + i.toLong() * elemSize
                sb.append(String.format("[%d] 0x%x: ", i, elemAddr))
                when (type) {
                    "int8" -> sb.append(data[i])
                    "uint8" -> sb.append(data[i].toInt() and 0xFF)
                    "int16" -> sb.append(buf.getShort(i * 2))
                    "uint16" -> sb.append(buf.getShort(i * 2).toInt() and 0xFFFF)
                    "int32" -> sb.append(buf.getInt(i * 4))
                    "uint32" -> sb.append(Integer.toUnsignedString(buf.getInt(i * 4)))
                    "float" -> sb.append(buf.getFloat(i * 4))
                    "int64" -> sb.append(buf.getLong(i * 8))
                    "uint64" -> sb.append(java.lang.Long.toUnsignedString(buf.getLong(i * 8)))
                    "double" -> sb.append(buf.getDouble(i * 8))
                    "pointer" -> {
                        val ptrVal = if (emulator.is64Bit()) buf.getLong(i * 8) else (buf.getInt(i * 4).toLong() and 0xFFFFFFFFL)
                        sb.append("0x").append(java.lang.Long.toHexString(ptrVal))
                        if (ptrVal != 0L) {
                            appendModuleAndSymbol(sb, memory, demangler, ptrVal)
                        }
                    }
                }
                sb.append('\n')
            }
            return textResult(sb.toString())
        } catch (e: Exception) {
            return errorResult("Failed to read typed data at 0x" + java.lang.Long.toHexString(address) + ": " + exMsg(e))
        }
    }

    private fun callFunction(args: JSONObject): JSONObject {
        if (emulator.isRunning()) {
            return errorResult("Cannot call function while emulator is running.")
        }
        val address = parseAddress(args.getString("address"))
        return doCallFunction(address, args)
    }

    private fun callSymbol(args: JSONObject): JSONObject {
        if (emulator.isRunning()) {
            return errorResult("Cannot call function while emulator is running.")
        }
        val moduleName = args.getString("module_name")
        val symbolName = args.getString("symbol_name")
        if (moduleName == null || moduleName.isEmpty()) {
            return errorResult("Missing required parameter 'module_name'.")
        }
        if (symbolName == null || symbolName.isEmpty()) {
            return errorResult("Missing required parameter 'symbol_name'.")
        }
        val module = emulator.getMemory().findModule(moduleName)
        if (module == null) {
            return errorResult("Module not found: $moduleName")
        }
        var symbol = module.findSymbolByName(symbolName, false)
        if (symbol == null) {
            symbol = module.findSymbolByName("_" + symbolName, false)
        }
        if (symbol == null) {
            return errorResult("Symbol '" + symbolName + "' not found in " + moduleName +
                    ". Use list_exports to see available symbols.")
        }
        return doCallFunction(symbol.getAddress(), args)
    }

    private fun doCallFunction(address: Long, args: JSONObject): JSONObject {
        val argsArray = args.getJSONArray("args")
        val funcArgs: Array<Any?>
        if (argsArray == null || argsArray.isEmpty()) {
            funcArgs = arrayOfNulls(0)
        } else {
            funcArgs = arrayOfNulls(argsArray.size)
            for (i in 0 until argsArray.size) {
                val argStr = argsArray.getString(i)
                try {
                    funcArgs[i] = parseCallArg(argStr)
                } catch (e: Exception) {
                    return errorResult("Invalid argument[" + i + "] '" + argStr + "': " + exMsg(e))
                }
            }
        }

        val sb = StringBuilder()

        val previewSize = if (args.containsKey("preview_size")) args.getIntValue("preview_size") else 64

        try {
            val result = Module.emulateFunction(emulator, address, *funcArgs)
            val retVal = result.toLong()
            sb.append("ret=0x").append(java.lang.Long.toHexString(retVal))
            if (retVal != 0L && retVal < 0x100000) {
                sb.append(" (").append(retVal).append(')')
            }

            val memory = emulator.getMemory()
            val retModule = memory.findModuleByAddress(retVal)
            if (retModule != null) {
                val demangler = DemanglerFactory.createDemangler()
                sb.append(' ').append(retModule.name).append("+0x").append(java.lang.Long.toHexString(retVal - retModule.base))
                val sym = retModule.findClosestSymbolByAddress(retVal, false)
                if (sym != null && retVal - sym.getAddress() <= Unwinder.SYMBOL_SIZE) {
                    sb.append(" <").append(demangler.demangle(sym.getName())).append('>')
                }
            }

            if (retVal > 0x1000 && previewSize > 0) {
                try {
                    val previewData = emulator.getBackend().mem_read(retVal, previewSize.toLong())
                    val str = tryPrintableString(previewData)
                    if (str != null) {
                        sb.append(" \"").append(str).append('"')
                    }
                } catch (ignored: Exception) {
                }
            }
            sb.append('\n')
            return textResult(sb.toString())
        } catch (e: Exception) {
            sb.append("\nCall FAILED: ").append(e.javaClass.name).append(": ").append(exMsg(e)).append('\n')
            val cause = e.cause
            if (cause != null) {
                sb.append("Caused by: ").append(cause.javaClass.name).append(": ").append(if (cause.message != null) cause.message else cause.javaClass.name).append('\n')
            }
            return errorResult(sb.toString())
        }
    }

    @Throws(DecoderException::class)
    private fun parseCallArg(argStr: String?): Any? {
        if (argStr == null || "null".equals(argStr, ignoreCase = true)) {
            return null
        }
        if (argStr.startsWith("s:")) {
            return argStr.substring(2)
        }
        if (argStr.startsWith("b:")) {
            return Hex.decodeHex(argStr.substring(2).toCharArray())
        }
        return parseAddress(argStr)
    }

    private fun listModules(args: JSONObject?): JSONObject {
        val filter = if (args != null) args.getString("filter") else null
        val modules = emulator.getMemory().getLoadedModules()
        val sb = StringBuilder()
        var count = 0
        for (m in modules) {
            if (filter != null && !filter.isEmpty() && !m.name.lowercase().contains(filter.lowercase())) {
                continue
            }
            sb.append(m.name).append(" 0x").append(java.lang.Long.toHexString(m.base))
                    .append(" 0x").append(java.lang.Long.toHexString(m.size)).append('\n')
            count++
        }
        sb.insert(0, count.toString() + " modules" + (if (filter != null && !filter.isEmpty()) " (filter: '" + filter + "', total: " + modules.size + ")" else "") + ":\n")
        return textResult(sb.toString())
    }

    private fun getModuleInfo(args: JSONObject): JSONObject {
        val moduleName = args.getString("module_name")
        val module = emulator.getMemory().findModule(moduleName)
        if (module == null) {
            return errorResult("Module not found: $moduleName")
        }
        val sb = StringBuilder()
        sb.append(module.name).append(" 0x").append(java.lang.Long.toHexString(module.base))
                .append(" size=0x").append(java.lang.Long.toHexString(module.size)).append('\n')
        val exports = module.getExportedSymbols()
        sb.append("exports=").append(exports.size)
        val deps = module.getNeededLibraries()
        if (!deps.isEmpty()) {
            sb.append(" deps=")
            var first = true
            for (dep in deps) {
                if (!first) sb.append(',')
                sb.append(dep.name)
                first = false
            }
        }
        sb.append('\n')
        return textResult(sb.toString())
    }

    private fun executeCustomTool(tool: CustomTool, args: JSONObject): JSONObject {
        val cmd = StringBuilder("run ")
        cmd.append(tool.name)
        for (pn in tool.paramNames) {
            val value = args.getString(pn)
            if (value != null) {
                cmd.append(' ').append(value)
            }
        }
        server.injectCommand(cmd.toString())
        return textResult("Emulation started: " + tool.name)
    }

    private fun listExports(args: JSONObject): JSONObject {
        val moduleName = args.getString("module_name")
        val filter = args.getString("filter")
        try {
            val module = emulator.getMemory().findModule(moduleName)
            if (module == null) {
                return errorResult("Module not found: $moduleName")
            }
            val symbols = module.getExportedSymbols()
            if (symbols.isEmpty()) {
                return textResult("No exported symbols in $moduleName")
            }
            val demangler = DemanglerFactory.createDemangler()
            val lines: MutableList<String> = ArrayList()
            for (symbol in symbols) {
                if (filter != null && !filter.isEmpty()) {
                    val name = symbol.getName()
                    val demangled = demangler.demangle(name)
                    if (!name.lowercase().contains(filter.lowercase()) &&
                            !demangled.lowercase().contains(filter.lowercase())) {
                        continue
                    }
                }
                val addr = symbol.getAddress()
                val demangled = demangler.demangle(symbol.getName())
                val line = "+0x" + java.lang.Long.toHexString(addr - module.base) + " " +
                        (if (demangled == symbol.getName()) symbol.getName() else demangled)
                lines.add(line)
            }
            val sb = StringBuilder()
            val truncated = lines.size > MAX_EXPORT_LINES && (filter == null || filter.isEmpty())
            if (filter != null && !filter.isEmpty()) {
                sb.append(String.format("Showing %d of %d symbols (filter: '%s')%n", lines.size, symbols.size, filter))
            } else {
                sb.append(String.format("%d exported symbol(s)%s:%n", lines.size,
                        if (truncated) " (showing first " + MAX_EXPORT_LINES + ", use filter to narrow)" else ""))
            }
            val limit = if (truncated) MAX_EXPORT_LINES else lines.size
            for (i in 0 until limit) {
                sb.append(lines[i]).append('\n')
            }
            if (truncated) {
                sb.append("... ").append(lines.size - MAX_EXPORT_LINES).append(" more symbols omitted. Use filter parameter to search.\n")
            }
            return textResult(sb.toString())
        } catch (e: Exception) {
            return errorResult("Failed to list exports: " + e.javaClass.name + ": " + exMsg(e))
        }
    }

    private fun getThreads(): JSONObject {
        try {
            val tasks = emulator.getThreadDispatcher().getTaskList()
            if (tasks.isEmpty()) {
                return textResult("No threads/tasks.")
            }
            val sb = StringBuilder()
            sb.append(String.format("%d thread(s):%n", tasks.size))
            for (task in tasks) {
                sb.append(String.format("  tid=%d: %s%n", task.getId(), task))
            }
            return textResult(sb.toString())
        } catch (e: Exception) {
            return errorResult("Failed to get threads: " + exMsg(e))
        }
    }

    private fun allocateMemory(args: JSONObject): JSONObject {
        val hexData = args.getString("data")
        var initData: ByteArray? = null
        if (hexData != null && !hexData.isEmpty()) {
            try {
                initData = Hex.decodeHex(hexData.toCharArray())
            } catch (e: DecoderException) {
                return errorResult("Invalid 'data' hex string: \"" + hexData + "\". Expected hex-encoded bytes, e.g. \"48656c6c6f\" for \"Hello\".")
            }
        }
        var size = if (args.containsKey("size")) args.getIntValue("size") else 0
        if (size <= 0 && initData != null) {
            size = initData.size
        }
        if (size <= 0) {
            return errorResult("Size must be positive. Provide 'size' or 'data' (hex-encoded bytes to infer size from).")
        }
        if (initData != null && initData.size > size) {
            return errorResult("Data length (" + initData.size + " bytes) exceeds allocation size (" + size + " bytes).")
        }
        val isRunning = emulator.isRunning()
        val runtimeParam = if (args.containsKey("runtime")) args.getBoolean("runtime") else null
        val runtime: Boolean
        if (isRunning) {
            if (runtimeParam != null && !runtimeParam) {
                return errorResult("Cannot use runtime=false (libc malloc) while emulator is running. " +
                        "Use runtime=true (mmap) or omit the parameter.")
            }
            runtime = true
        } else {
            runtime = if (runtimeParam != null) runtimeParam else false
        }
        try {
            val block = emulator.getMemory().malloc(size, runtime)
            val pointer = block.getPointer()
            allocatedBlocks.put(pointer.peer, Allocation(block, runtime, size))
            if (initData != null) {
                pointer.write(0, initData, 0, initData.size)
            }
            val sb = StringBuilder()
            sb.append("0x").append(java.lang.Long.toHexString(pointer.peer))
                    .append(" (").append(size).append(" bytes, ").append(if (runtime) "mmap" else "malloc").append(')')
            if (initData != null) {
                sb.append(" +data")
            }
            return textResult(sb.toString())
        } catch (e: Exception) {
            return errorResult("Failed to allocate memory: " + exMsg(e))
        }
    }

    private fun freeMemory(args: JSONObject): JSONObject {
        val address = parseAddress(args.getString("address"))
        val alloc = allocatedBlocks[address]
        if (alloc == null) {
            return errorResult("No tracked allocation at 0x" + java.lang.Long.toHexString(address) +
                    ". Only blocks allocated via allocate_memory can be freed.")
        }
        if (!alloc.runtime && emulator.isRunning()) {
            return errorResult("Cannot free malloc-allocated memory at 0x" + java.lang.Long.toHexString(address) +
                    " while emulator is running. malloc blocks require isRunning=false to call libc free()." +
                    " Wait until emulator stops first.")
        }
        try {
            alloc.block.free()
            allocatedBlocks.remove(address)
            return textResult("Freed 0x" + java.lang.Long.toHexString(address))
        } catch (e: Exception) {
            return errorResult("Failed to free memory at 0x" + java.lang.Long.toHexString(address) + ": " + exMsg(e))
        }
    }

    private fun listAllocations(): JSONObject {
        if (allocatedBlocks.isEmpty()) {
            return textResult("No active allocations.")
        }
        val sb = StringBuilder()
        sb.append(String.format("%d active allocation(s):%n", allocatedBlocks.size))
        for (entry in allocatedBlocks.entries) {
            val addr = entry.key
            val alloc = entry.value
            val type = if (alloc.runtime) "mmap (free anytime)" else "malloc (free requires isRunning=false)"
            sb.append(String.format("  0x%x  size=%d (0x%x)  type=%s%n", addr, alloc.size, alloc.size, type))
        }
        return textResult(sb.toString())
    }

    private fun createKeystone(): Keystone {
        if (emulator.is64Bit()) {
            return Keystone(KeystoneArchitecture.Arm64, KeystoneMode.LittleEndian)
        } else {
            val thumb = ARM.isThumb(emulator.getBackend())
            return Keystone(KeystoneArchitecture.Arm, if (thumb) KeystoneMode.ArmThumb else KeystoneMode.Arm)
        }
    }

    private fun resolveRegister(name: String): Int {
        if (emulator.is64Bit()) {
            if (name.startsWith("X")) {
                val num = Integer.parseInt(name.substring(1))
                if (num in 0..28) {
                    return Arm64Const.UC_ARM64_REG_X0 + num
                } else if (num == 29) {
                    return Arm64Const.UC_ARM64_REG_FP
                } else if (num == 30) {
                    return Arm64Const.UC_ARM64_REG_LR
                }
                throw IllegalArgumentException("Invalid X register number: $num")
            }
            if (name.startsWith("W")) {
                val num = Integer.parseInt(name.substring(1))
                if (num in 0..30) {
                    return Arm64Const.UC_ARM64_REG_W0 + num
                }
                throw IllegalArgumentException("Invalid W register number: $num")
            }
            when (name) {
                "SP" -> return Arm64Const.UC_ARM64_REG_SP
                "PC" -> return Arm64Const.UC_ARM64_REG_PC
                "LR" -> return Arm64Const.UC_ARM64_REG_LR
                "FP" -> return Arm64Const.UC_ARM64_REG_FP
                else -> throw IllegalArgumentException("Unknown ARM64 register: $name")
            }
        } else {
            if (name.startsWith("R")) {
                val num = Integer.parseInt(name.substring(1))
                if (num in 0..12) {
                    return ArmConst.UC_ARM_REG_R0 + num
                } else if (num == 13) {
                    return ArmConst.UC_ARM_REG_SP
                } else if (num == 14) {
                    return ArmConst.UC_ARM_REG_LR
                } else if (num == 15) {
                    return ArmConst.UC_ARM_REG_PC
                }
                throw IllegalArgumentException("Invalid R register number: $num")
            }
            when (name) {
                "SP" -> return ArmConst.UC_ARM_REG_SP
                "PC" -> return ArmConst.UC_ARM_REG_PC
                "LR" -> return ArmConst.UC_ARM_REG_LR
                "FP" -> return ArmConst.UC_ARM_REG_FP
                "IP" -> return ArmConst.UC_ARM_REG_IP
                else -> throw IllegalArgumentException("Unknown ARM register: $name")
            }
        }
    }

    companion object {
        private fun getBackendCapabilities(backendClass: String): String {
            if (backendClass.contains("Unicorn")) return "FULL"
            if (backendClass.contains("Hypervisor")) return "PARTIAL"
            if (backendClass.contains("Dynarmic") || backendClass.contains("Kvm")) return "MINIMAL"
            return "unknown"
        }

        private fun hexDump(data: ByteArray, baseAddr: Long): String {
            val sb = StringBuilder()
            var i = 0
            while (i < data.size) {
                sb.append(String.format("%08x: ", baseAddr + i))
                val end = Math.min(i + 16, data.size)
                for (j in i until i + 16) {
                    if (j < end) {
                        sb.append(String.format("%02X ", data[j]))
                    } else {
                        sb.append("   ")
                    }
                }
                sb.append(' ')
                for (j in i until end) {
                    val c = (data[j].toInt() and 0xFF).toChar()
                    sb.append(if (c.code >= 0x20 && c.code <= 0x7e) c else '.')
                }
                sb.append('\n')
                i += 16
            }
            return sb.toString()
        }

        private fun matchPattern(data: ByteArray, offset: Int, pattern: ByteArray, mask: ByteArray?): Boolean {
            for (j in pattern.indices) {
                if (mask != null) {
                    if ((data[offset + j].toInt() and mask[j].toInt()) != (pattern[j].toInt() and mask[j].toInt())) {
                        return false
                    }
                } else {
                    if (data[offset + j] != pattern[j]) {
                        return false
                    }
                }
            }
            return true
        }

        private val IMM_ADDR_PATTERN: java.util.regex.Pattern = java.util.regex.Pattern.compile("#0x([0-9a-fA-F]+)")

        private fun isBranchMnemonic(mnemonic: String): Boolean {
            when (mnemonic) {
                "b", "bl", "br", "blr",
                "cbz", "cbnz", "tbz", "tbnz",
                "bx", "blx" ->
                    return true
                else -> {
                    if (mnemonic.startsWith("b.")) return true
                    if (mnemonic.startsWith("bl") && mnemonic.length <= 5) return true
                    return mnemonic.startsWith("b") && mnemonic.length <= 4 &&
                            !mnemonic.startsWith("bic") && !mnemonic.startsWith("bfi") && !mnemonic.startsWith("bfc")
                }
            }
        }

        private const val MAX_EXPORT_LINES = 200

        private fun resolvePermission(permission: String?): Int {
            if (permission == null || permission.isEmpty() || "write".equals(permission, ignoreCase = true)) {
                return UnicornConst.UC_PROT_WRITE
            }
            if ("read".equals(permission, ignoreCase = true)) {
                return UnicornConst.UC_PROT_READ
            }
            if ("execute".equals(permission, ignoreCase = true)) {
                return UnicornConst.UC_PROT_EXEC
            }
            return UnicornConst.UC_PROT_WRITE
        }

        private fun parseAddress(address: String?): Long {
            if (address == null) return 0
            val addr = address.trim()
            if (addr.startsWith("0x") || addr.startsWith("0X")) {
                return java.lang.Long.parseUnsignedLong(addr.substring(2), 16)
            }
            return java.lang.Long.parseUnsignedLong(addr, 16)
        }

        private fun permString(prot: Int): String {
            return (if ((prot and 1) != 0) "r" else "-") +
                    (if ((prot and 2) != 0) "w" else "-") +
                    (if ((prot and 4) != 0) "x" else "-")
        }

        private fun textResult(text: String): JSONObject {
            val result = JSONObject(true)
            val content = JSONArray()
            val item = JSONObject(true)
            item.put("type", "text")
            item.put("text", text)
            content.add(item)
            result.put("content", content)
            return result
        }

        @JvmStatic
        fun errorResult(message: String): JSONObject {
            val result = textResult(message)
            result.put("isError", true)
            return result
        }

        private fun tryPrintableString(data: ByteArray): String? {
            var len = 0
            while (len < data.size && data[len].toInt() != 0) {
                if (data[len] < 0x20 || data[len] > 0x7e) return null
                len++
            }
            return if (len > 0) String(data, 0, len, java.nio.charset.StandardCharsets.UTF_8) else null
        }

        private fun exMsg(e: Exception): String {
            val msg = e.message
            if (msg == null || msg.isEmpty()) {
                return e.javaClass.name
            }
            return msg
        }

        private fun toolSchema(name: String, description: String, vararg params: JSONObject): JSONObject {
            val schema = JSONObject(true)
            schema.put("name", name)
            schema.put("description", description)
            val inputSchema = JSONObject(true)
            inputSchema.put("type", "object")
            if (params.size > 0) {
                val properties = JSONObject(true)
                for (p in params) {
                    properties.put(p.getString("_name"), p)
                    p.remove("_name")
                }
                inputSchema.put("properties", properties)
            }
            schema.put("inputSchema", inputSchema)
            return schema
        }

        private fun putModuleInfo(event: JSONObject, emu: Emulator<*>, address: Long) {
            val module = emu.getMemory().findModuleByAddress(address)
            if (module != null) {
                event.put("module", module.name)
                event.put("offset", "0x" + java.lang.Long.toHexString(address - module.base))
            }
        }

        private fun buildInputSchema(vararg paramNames: String): JSONObject {
            val inputSchema = JSONObject(true)
            inputSchema.put("type", "object")
            if (paramNames.size > 0) {
                val properties = JSONObject(true)
                val required = JSONArray()
                for (pn in paramNames) {
                    val p = JSONObject(true)
                    p.put("type", "string")
                    properties.put(pn, p)
                    required.add(pn)
                }
                inputSchema.put("properties", properties)
                inputSchema.put("required", required)
            }
            return inputSchema
        }

        private fun param(name: String, type: String, description: String): JSONObject {
            val p = JSONObject(true)
            p.put("_name", name)
            p.put("type", type)
            p.put("description", description)
            return p
        }

        private fun argsParam(): JSONObject {
            val p = JSONObject(true)
            p.put("_name", "args")
            p.put("type", "array")
            val items = JSONObject(true)
            items.put("type", "string")
            p.put("items", items)
            p.put("description", "Args array, format in instructions.")
            return p
        }
    }

    private class CustomTool(@JvmField val name: String, @JvmField val description: String, paramNames: Array<String>?) {
        @JvmField val paramNames: Array<String> = paramNames ?: arrayOf()
    }
}
