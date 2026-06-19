package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.mcp.McpTools
import java.io.File
import java.util.zip.ZipFile

/**
 * DVM/Java MCP sub-handler: static DEX surface (dvm_dex_surface).
 *
 * Lists/searches the app's DEX classes, methods and strings using a minimal in-repo DEX parser
 * ([DexReader], no external dependency). DEX bytes come from the VM's bundled APK when it was created
 * with one (vm.unzip("classes.dex")), or from an explicit `path` (an .apk/.zip or a .dex file). Method
 * entries are emitted as "internalClass : name(args)ret" so the class + signature can be pasted
 * straight into dvm_call_static / dvm_resolve_method.
 *
 * Tool:
 *  - `dvm_dex_surface`: list/search the static DEX surface (classes, methods or strings).
 *    Example prompt: "List every DEX method whose name contains 'decrypt'."
 */
class DvmDexTools(private val emulator: Emulator<*>, private val vm: VM) : DvmSubTools {

    override fun handles(name: String): Boolean = name == "dvm_dex_surface"

    override fun schemas(): JSONArray {
        val tools = JSONArray()
        tools.add(DvmSupport.schema("dvm_dex_surface",
                "[DVM/Java] List or search the app's DEX static surface (classes, methods or strings) " +
                        "via an in-repo DEX parser. Reads classes*.dex from the VM's bundled APK (if it was " +
                        "created with one) or from an explicit path. Method entries are " +
                        "'internalClass : name(args)ret', ready to paste into dvm_call_static.",
                DvmSupport.param("kind", "What to list: class | method | string. Default class."),
                DvmSupport.param("query", "Optional. Substring (or regex) filter."),
                DvmSupport.param("regex", "Optional. true to treat query as a regex."),
                DvmSupport.param("path", "Optional. Path to an .apk/.zip or a .dex file. If omitted, uses the " +
                        "VM's bundled APK (requires createDalvikVM(apkFile))."),
                DvmSupport.param("max", "Optional. Max results. Default 200.")))
        return tools
    }

    override fun call(name: String, args: JSONObject): JSONObject {
        if (name != "dvm_dex_surface") return McpTools.errorResult("DvmDexTools cannot handle tool: $name")
        return try {
            dexSurface(args)
        } catch (e: Exception) {
            McpTools.errorResult("dvm_dex_surface failed: " + (e.message ?: e.javaClass.name))
        }
    }

    private fun dexSurface(args: JSONObject): JSONObject {
        val dexes = loadDexBytes(args)
        if (dexes.isEmpty()) {
            return McpTools.errorResult("No DEX available. The VM has no bundled APK (created without one); " +
                    "pass path=<apk-or-dex>, e.g. tests/keychain-aes-test/out/keychain-aes.apk")
        }
        val kind = (args.getString("kind") ?: "class").trim().lowercase()
        val query = args.getString("query")?.takeIf { it.isNotEmpty() }
        val regex = parseBool(args.get("regex"))
        val max = DvmSupport.parseHashInt(args.getString("max"))?.takeIf { it > 0 } ?: 200

        val items = ArrayList<String>()
        var totalStrings = 0
        var totalClasses = 0
        var totalMethods = 0
        for (dex in dexes) {
            val info = DexReader.parse(dex)
            totalClasses += info.classes.size
            totalMethods += info.methods.size
            totalStrings += info.strings.size
            when (kind) {
                "method" -> items.addAll(info.methods)
                "string" -> items.addAll(info.strings)
                else -> items.addAll(info.classes)
            }
        }

        val matcher: (String) -> Boolean = when {
            query == null -> { _ -> true }
            regex -> {
                val re = Regex(query)
                ({ s: String -> re.containsMatchIn(s) })
            }
            else -> ({ s: String -> s.contains(query) })
        }
        val filtered = items.asSequence().filter(matcher).toList()

        val sb = StringBuilder()
        sb.append("DEX ").append(kind).append(" surface (").append(dexes.size).append(" dex file(s); ")
                .append(totalClasses).append(" classes, ").append(totalMethods).append(" methods, ")
                .append(totalStrings).append(" strings)\n")
        if (query != null) sb.append("filter: ").append(if (regex) "/$query/" else "'$query'").append('\n')
        sb.append("matches: ").append(filtered.size)
        if (filtered.size > max) sb.append(" (showing first ").append(max).append(")")
        sb.append('\n')
        for (s in filtered.take(max)) {
            sb.append("  ").append(s).append('\n')
        }
        return McpTools.textResult(sb.toString())
    }

    /** DEX byte arrays from an explicit path (apk/zip or .dex) or the VM's bundled APK. */
    private fun loadDexBytes(args: JSONObject): List<ByteArray> {
        val path = args.getString("path")?.takeIf { it.isNotEmpty() }
        if (path != null) {
            val f = File(path)
            if (!f.isFile) throw IllegalArgumentException("No such file: $path")
            val lower = path.lowercase()
            if (lower.endsWith(".apk") || lower.endsWith(".zip")) {
                val out = ArrayList<ByteArray>()
                ZipFile(f).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (!e.isDirectory && e.name.matches(Regex("classes\\d*\\.dex"))) {
                            zf.getInputStream(e).use { out.add(it.readBytes()) }
                        }
                    }
                }
                return out
            }
            return listOf(f.readBytes())
        }
        // From the VM's bundled APK (classes.dex, classes2.dex, ...).
        val base = DvmSupport.baseVm(vm)
        val out = ArrayList<ByteArray>()
        base.unzip("classes.dex")?.let { out.add(it) }
        var i = 2
        while (true) {
            val b = base.unzip("classes$i.dex") ?: break
            out.add(b)
            i++
        }
        return out
    }

    private fun parseBool(raw: Any?): Boolean {
        if (raw == null) return false
        if (raw is Boolean) return raw
        return when (raw.toString().trim().lowercase()) {
            "true", "1", "yes" -> true
            else -> false
        }
    }
}
