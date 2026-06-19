package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.mcp.McpTools
import org.apache.commons.codec.binary.Hex

/**
 * Discovery / probing sub-handler for the DVM/Java MCP tools.
 *
 * Provides three tools that build on top of [DvmSupport] (schemas, arg marshalling and static-call
 * dispatch) without duplicating any of that logic:
 *  - `dvm_resolve_method`: search resolved DVM classes for native-bound methods by bare name.
 *    Example prompt: "Find every class that exposes a native method called 'seal'."
 *  - `dvm_oracle`: call a static method and assert its rendered result against an expected value.
 *    Example prompt: "Does com/example/Foo.encode('abc') return 'cd9a...'? Check it."
 *  - `dvm_fuzz_method`: call a static method repeatedly varying one argument and tabulate outputs.
 *    Example prompt: "Fuzz Foo.seal over these 50 inputs and tell me which output bytes change."
 */
class DvmDiscoveryTools(private val emulator: Emulator<*>, private val vm: VM) : DvmSubTools {

    private val names = setOf("dvm_resolve_method", "dvm_oracle", "dvm_fuzz_method")

    override fun handles(name: String): Boolean = names.contains(name)

    override fun schemas(): JSONArray {
        val tools = JSONArray()
        tools.add(DvmSupport.schema("dvm_resolve_method",
                "[DVM/Java] Find candidate methods across the resolved DVM classes by bare method name. " +
                        "Searches each class's native-bound method table (the signatures Vortex has wired to native " +
                        "code); reports 'class -> signature' candidates and whether they are native-bound. Note: only " +
                        "natively-registered methods are visible here; arbitrary private Java method maps are not enumerable.",
                DvmSupport.param("name", "Bare method name to look for, e.g. seal."),
                DvmSupport.param("class", "Optional. Class name in slash form to restrict the search, e.g. com/example/Foo."),
                DvmSupport.param("native_only", "Optional. 'true' to only return native-bound candidates (default true).")))
        tools.add(DvmSupport.schema("dvm_oracle",
                "[DVM/Java] Call a STATIC Java method and assert its result against an expected value. " +
                        "Returns MATCH/MISMATCH with both the actual and expected values. Requires the emulator stopped.",
                DvmSupport.param("class", "Class name in slash form, e.g. com/example/Foo."),
                DvmSupport.param("method", "JNI method signature, e.g. seal(Ljava/lang/String;)Ljava/lang/String;"),
                DvmSupport.param("args", "Optional. JSON array of string arguments matching the signature.", "array"),
                DvmSupport.param("expect", "Expected value to compare the rendered result against."),
                DvmSupport.param("match", "Optional match mode: exact (default), hex, or contains.")))
        tools.add(DvmSupport.schema("dvm_fuzz_method",
                "[DVM/Java] Call a STATIC Java method repeatedly, varying exactly one argument slot over a list of " +
                        "inputs, and tabulate input -> output. If all outputs are equal-length hex byte strings, also " +
                        "reports which byte positions stay constant vs vary. Capped at 256 iterations. Requires the emulator stopped.",
                DvmSupport.param("class", "Class name in slash form, e.g. com/example/Foo."),
                DvmSupport.param("method", "JNI method signature with one varying arg, e.g. seal(Ljava/lang/String;)Ljava/lang/String;"),
                DvmSupport.param("inputs", "JSON array of string values to feed into the varying arg slot.", "array"),
                DvmSupport.param("arg_index", "Index of the varying argument slot (0-based).", "integer"),
                DvmSupport.param("fixed_args", "Optional. JSON array of the other (fixed) args; fixed_args.size + 1 must equal the arg count.", "array")))
        return tools
    }

    override fun call(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "dvm_resolve_method" -> resolveMethod(args)
                "dvm_oracle" -> oracle(args)
                "dvm_fuzz_method" -> fuzzMethod(args)
                else -> McpTools.errorResult("Unknown DVM discovery tool: $name")
            }
        } catch (e: Exception) {
            McpTools.errorResult("DVM tool '$name' failed: " + (e.message ?: e.javaClass.name))
        }
    }

    // ---------- dvm_resolve_method ----------

    /** Bare method name encoded in a nativesMap key (key may be a full JNI sig or a bare name). */
    private fun bareName(key: String): String {
        val open = key.indexOf('(')
        return if (open >= 0) key.substring(0, open) else key
    }

    private fun parseBool(raw: String?, default: Boolean): Boolean {
        if (raw == null) return default
        val s = raw.trim().toLowerCase()
        return s == "true" || s == "1" || s == "yes"
    }

    private fun resolveMethod(args: JSONObject): JSONObject {
        val target = args.getString("name")
                ?: return McpTools.errorResult("Missing required parameter 'name' (bare method name).")
        val classFilter = args.getString("class")
        val nativeOnly = parseBool(args.getString("native_only"), true)

        val classes = DvmSupport.baseVm(vm).classMap.values
        val sb = StringBuilder()
        var matches = 0
        var scanned = 0

        for (c in classes) {
            val className = c.getClassName()
            if (classFilter != null && className != classFilter && className != classFilter.replace('.', '/')) {
                continue
            }
            scanned++
            val natives = c.nativesMap
            for (key in natives.keys) {
                if (bareName(key) == target) {
                    matches++
                    sb.append("  ").append(className).append(" -> ").append(key).append("  [native-bound]\n")
                }
            }
        }

        val header = StringBuilder()
        header.append("dvm_resolve_method name='").append(target).append("'")
        if (classFilter != null) header.append(" class='").append(classFilter).append("'")
        header.append(" native_only=").append(nativeOnly)
        header.append(" : scanned ").append(scanned).append(" class(es), ")
                .append(matches).append(" native-bound candidate(s).\n")
        if (matches == 0) {
            header.append("Note: this tool only enumerates native-bound methods (DvmClass.nativesMap). " +
                    "Methods bound implicitly by symbol lookup at call time, or pure-Java methods, are not listed here.\n")
        }
        header.append(sb)
        return McpTools.textResult(header.toString())
    }

    // ---------- dvm_oracle ----------

    /**
     * Normalise a rendered result for comparison: strip surrounding quotes from string renders and
     * the trailing " (N bytes)" annotation from byte-array renders so the bare hex remains.
     */
    private fun normalizeRendered(rendered: String): String {
        var s = rendered
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        val bytesIdx = s.indexOf(" (")
        if (bytesIdx >= 0 && s.endsWith(" bytes)")) {
            s = s.substring(0, bytesIdx)
        }
        return s
    }

    private fun compare(actual: String, expect: String, mode: String): Boolean {
        return when (mode) {
            "contains" -> actual.contains(expect)
            "hex" -> actual.replace(" ", "").equals(expect.replace(" ", ""), ignoreCase = true)
            else -> actual == expect
        }
    }

    private fun oracle(args: JSONObject): JSONObject {
        if (emulator.isRunning()) {
            return McpTools.errorResult("Cannot call a Java method while the emulator is running.")
        }
        val className = args.getString("class")
                ?: return McpTools.errorResult("Missing required parameter 'class' (slash form).")
        val method = args.getString("method")
                ?: return McpTools.errorResult("Missing required parameter 'method' (JNI signature).")
        val expect = args.getString("expect")
                ?: return McpTools.errorResult("Missing required parameter 'expect'.")
        val mode = (args.getString("match") ?: "exact").trim().toLowerCase()
        if (mode != "exact" && mode != "hex" && mode != "contains") {
            return McpTools.errorResult("Invalid 'match' mode '$mode' (expected exact|hex|contains).")
        }

        val result = DvmSupport.callStatic(emulator, vm, className, method, args.getJSONArray("args"))
        val normalized = normalizeRendered(result.rendered)
        val expectNorm = normalizeRendered(expect)
        val ok = compare(normalized, expectNorm, mode)

        val sb = StringBuilder()
        sb.append(if (ok) "MATCH" else "MISMATCH").append(" (mode=").append(mode).append(")\n")
        sb.append("  actual  : ").append(result.rendered).append('\n')
        sb.append("  expected: ").append(expect).append('\n')
        return McpTools.textResult(sb.toString())
    }

    // ---------- dvm_fuzz_method ----------

    private val maxIterations = 256

    private fun fuzzMethod(args: JSONObject): JSONObject {
        if (emulator.isRunning()) {
            return McpTools.errorResult("Cannot call a Java method while the emulator is running.")
        }
        val className = args.getString("class")
                ?: return McpTools.errorResult("Missing required parameter 'class' (slash form).")
        val method = args.getString("method")
                ?: return McpTools.errorResult("Missing required parameter 'method' (JNI signature).")
        val inputs = args.getJSONArray("inputs")
                ?: return McpTools.errorResult("Missing required parameter 'inputs' (JSON array of values).")
        if (inputs.isEmpty()) {
            return McpTools.errorResult("'inputs' must contain at least one value.")
        }
        val argIndexObj = args.get("arg_index")
                ?: return McpTools.errorResult("Missing required parameter 'arg_index'.")
        val argIndex = try {
            argIndexObj.toString().trim().toInt()
        } catch (e: NumberFormatException) {
            return McpTools.errorResult("'arg_index' must be an integer.")
        }
        if (argIndex < 0) {
            return McpTools.errorResult("'arg_index' must be >= 0.")
        }

        // Determine the arg count from the signature.
        val open = method.indexOf('(')
        val close = method.indexOf(')')
        if (open < 0 || close <= open) {
            return McpTools.errorResult("Invalid method signature: $method")
        }
        val argTypes = DvmSupport.parseArgTypes(method.substring(open + 1, close))
        val argCount = argTypes.size

        if (argIndex >= argCount) {
            return McpTools.errorResult("'arg_index' $argIndex out of range for a $argCount-arg signature.")
        }
        val fixed = args.getJSONArray("fixed_args") ?: JSONArray()
        if (fixed.size + 1 != argCount) {
            return McpTools.errorResult("fixed_args.size (" + fixed.size + ") + 1 must equal the arg count (" +
                    argCount + ").")
        }

        var total = inputs.size
        var truncated = false
        if (total > maxIterations) {
            total = maxIterations
            truncated = true
        }

        val sb = StringBuilder()
        sb.append("dvm_fuzz_method ").append(className).append('.')
                .append(method.substring(0, open)).append(" varying arg[").append(argIndex).append("]\n")
        if (truncated) {
            sb.append("NOTE: ").append(inputs.size).append(" inputs provided; capped to ")
                    .append(maxIterations).append(".\n")
        }
        sb.append("input -> output:\n")

        val outputs = ArrayList<String>(total)
        for (i in 0 until total) {
            val input = inputs.getString(i)
            // Build the full args array by inserting input at argIndex among the fixed args.
            val full = JSONArray()
            var f = 0
            for (slot in 0 until argCount) {
                if (slot == argIndex) {
                    full.add(input)
                } else {
                    full.add(fixed.getString(f))
                    f++
                }
            }
            val rendered = try {
                DvmSupport.callStatic(emulator, vm, className, method, full).rendered
            } catch (e: Exception) {
                "<error: " + (e.message ?: e.javaClass.name) + ">"
            }
            outputs.add(rendered)
            sb.append("  ").append(input).append(" -> ").append(rendered).append('\n')
        }

        appendVariance(sb, outputs)
        return McpTools.textResult(sb.toString())
    }

    /**
     * If every output normalises to an equal-length hex byte string, summarise which byte positions
     * are constant across all outputs vs which vary.
     */
    private fun appendVariance(sb: StringBuilder, outputs: List<String>) {
        if (outputs.size < 2) return
        val hexes = ArrayList<kotlin.ByteArray>(outputs.size)
        for (out in outputs) {
            val h = normalizeRendered(out).replace(" ", "")
            val bytes = try {
                Hex.decodeHex(h.toCharArray())
            } catch (e: Exception) {
                return // not all hex -> skip variance analysis
            }
            hexes.add(bytes)
        }
        val len = hexes[0].size
        for (b in hexes) {
            if (b.size != len) return // unequal length -> skip
        }
        if (len == 0) return

        val constant = ArrayList<Int>()
        val varying = ArrayList<Int>()
        for (pos in 0 until len) {
            val first = hexes[0][pos]
            var same = true
            for (b in hexes) {
                if (b[pos] != first) {
                    same = false
                    break
                }
            }
            if (same) constant.add(pos) else varying.add(pos)
        }
        sb.append("byte variance over ").append(hexes.size).append(" output(s) of ").append(len)
                .append(" byte(s):\n")
        sb.append("  constant positions (").append(constant.size).append("): ")
                .append(formatPositions(constant)).append('\n')
        sb.append("  varying positions  (").append(varying.size).append("): ")
                .append(formatPositions(varying)).append('\n')
    }

    private fun formatPositions(positions: List<Int>): String {
        if (positions.isEmpty()) return "(none)"
        val sb = StringBuilder()
        for ((i, p) in positions.withIndex()) {
            if (i > 0) sb.append(", ")
            sb.append(p)
        }
        return sb.toString()
    }
}
