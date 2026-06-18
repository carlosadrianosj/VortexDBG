package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.BaseVM
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.linux.android.dvm.array.BaseArray
import com.vortexdbg.mcp.McpTools

/**
 * [DvmSubTools] group of read-only DVM/Java introspection tools. None of these invoke an emulated
 * method or construct an object, so there is no need to guard against a running emulator. They walk
 * the live DVM data structures (the class map and the three JNI ref maps) and the class/superclass/
 * interface graph to describe what Vortex currently holds.
 *
 * Tools:
 *  - `dvm_class_hierarchy`: print a class's superclass chain to the root plus its declared interfaces.
 *  - `dvm_inspect_object`: describe one object by JNI hash (class, scope, refCount, weak, preview, array length).
 *  - `dvm_ref_table_stats`: sizes of the class map and the three ref maps, with a per-class histogram of live objects.
 *  - `dvm_search_classes`: search resolved classes by name (substring or regex).
 *  - `dvm_object_graph`: list live refs across the three maps, grouped by class or scope, with a limit.
 *  - `dvm_find_objects_by_class`: list live object handles whose class matches (slash or dot form).
 *  - `dvm_pending_exception`: show the currently pending thrown exception, if any.
 */
class DvmIntrospectTools(private val emulator: Emulator<*>, private val vm: VM) : DvmSubTools {

    private val names = setOf(
            "dvm_class_hierarchy",
            "dvm_inspect_object",
            "dvm_ref_table_stats",
            "dvm_search_classes",
            "dvm_object_graph",
            "dvm_find_objects_by_class",
            "dvm_pending_exception")

    override fun handles(name: String): Boolean = names.contains(name)

    override fun schemas(): JSONArray {
        val tools = JSONArray()
        tools.add(DvmSupport.schema("dvm_class_hierarchy",
                "[DVM/Java] Walk the superclass chain of a resolved DVM class up to the root and list its declared " +
                        "interfaces, printing the chain. The class must already have been resolved (findClass); only " +
                        "classes Vortex currently holds in its class map are visible.",
                DvmSupport.param("class", "Class name to inspect (slash or dot form, e.g. java/lang/String or java.lang.String).")))
        tools.add(DvmSupport.schema("dvm_inspect_object",
                "[DVM/Java] Describe one live DVM object by its JNI hash handle: its class (getObjectType), the " +
                        "reference scope (local/global/weak) with refCount and weak flag found by probing the three ref " +
                        "maps, a value preview, and (for byte/int/... arrays) the array length.",
                DvmSupport.param("hash", "Decimal or 0x-hex JNI hash handle of the object.")))
        tools.add(DvmSupport.schema("dvm_ref_table_stats",
                "[DVM/Java] Report sizes of the DVM class map and the three JNI ref maps (local/global/weak) plus a " +
                        "histogram of live objects grouped by class name (getObjectType). Note: the class map and the " +
                        "global map overlap because resolved classes are themselves registered as global refs."))
        tools.add(DvmSupport.schema("dvm_search_classes",
                "[DVM/Java] Search the resolved DVM classes by name. Matches against both the slash form (getClassName) " +
                        "and the dot form (getName). Substring match by default, or a regular expression if regex=true.",
                DvmSupport.param("query", "Name substring, or a regex pattern when regex=true."),
                DvmSupport.param("regex", "Optional. If true, treat query as a regular expression. Default false.", "boolean")))
        tools.add(DvmSupport.schema("dvm_object_graph",
                "[DVM/Java] List all live JNI refs across the local/global/weak maps, grouped either by class or by " +
                        "scope, with hash, refCount, weak flag and a value preview for each. Use limit to cap output " +
                        "(truncation is noted). Resolved classes appear here as global refs of class java/lang/Class.",
                DvmSupport.param("group_by", "Optional. 'class' (default) or 'scope'."),
                DvmSupport.param("limit", "Optional. Max number of refs to print (default 200). Truncation is reported.", "integer")))
        tools.add(DvmSupport.schema("dvm_find_objects_by_class",
                "[DVM/Java] List the live object handles (across local/global/weak maps) whose class (getObjectType) " +
                        "matches the given class name. Accepts the slash form or the dot form; the comparison is done " +
                        "on the normalized slash form.",
                DvmSupport.param("class", "Class name to match (slash or dot form).")))
        tools.add(DvmSupport.schema("dvm_pending_exception",
                "[DVM/Java] Show the DVM's currently pending thrown exception (BaseVM.getPendingException): its class " +
                        "and value, or 'none' if no exception is pending. Note: deleteLocalRefs() clears the pending " +
                        "exception after each JNI call."))
        return tools
    }

    override fun call(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "dvm_class_hierarchy" -> doClassHierarchy(args)
                "dvm_inspect_object" -> doInspectObject(args)
                "dvm_ref_table_stats" -> doRefTableStats()
                "dvm_search_classes" -> doSearchClasses(args)
                "dvm_object_graph" -> doObjectGraph(args)
                "dvm_find_objects_by_class" -> doFindObjectsByClass(args)
                "dvm_pending_exception" -> doPendingException()
                else -> McpTools.errorResult("Unknown DVM introspect tool: $name")
            }
        } catch (e: Exception) {
            McpTools.errorResult("DVM introspect tool '$name' failed: " + (e.message ?: e.javaClass.name))
        }
    }

    // ---------- dvm_class_hierarchy ----------

    private fun doClassHierarchy(args: JSONObject): JSONObject {
        val raw = args.getString("class")
        if (raw == null || raw.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'class'.")
        }
        val name = raw.trim().replace('.', '/')
        val base = DvmSupport.baseVm(vm)
        val cls = base.findClass(name)
                ?: return McpTools.errorResult("No resolved class named '" + name + "'. Use dvm_search_classes to find resolved classes.")
        val sb = StringBuilder()
        sb.append("class hierarchy for ").append(cls.getClassName()).append(":\n")
        sb.append("superclass chain (most-derived first):\n")
        var cur: DvmClass? = cls
        var depth = 0
        val seen = HashSet<String>()
        while (cur != null) {
            for (i in 0 until depth) sb.append("  ")
            sb.append(if (depth == 0) "* " else "-> ").append(cur.getClassName()).append('\n')
            if (!seen.add(cur.getClassName())) {
                sb.append("  (cycle detected, stopping)\n")
                break
            }
            cur = cur.getSuperclass()
            depth++
        }
        val ifaces = cls.getInterfaces()
        sb.append("interfaces of ").append(cls.getClassName()).append(":\n")
        if (ifaces == null || ifaces.isEmpty()) {
            sb.append("  (none declared)\n")
        } else {
            for (i in ifaces) sb.append("  - ").append(i.getClassName()).append('\n')
        }
        return McpTools.textResult(sb.toString())
    }

    // ---------- dvm_inspect_object ----------

    private fun doInspectObject(args: JSONObject): JSONObject {
        val hash = DvmSupport.parseHashInt(args.getString("hash"))
                ?: return McpTools.errorResult("Missing/invalid 'hash' (decimal or 0x-hex JNI hash handle).")
        val base = DvmSupport.baseVm(vm)
        val obj: DvmObject<*>? = base.getObject(hash)
        if (obj == null) {
            return McpTools.errorResult("No object found for hash 0x" + Integer.toHexString(hash) + ".")
        }
        val sb = StringBuilder()
        sb.append("object 0x").append(Integer.toHexString(hash)).append(" (").append(hash).append("):\n")
        sb.append("  class: ").append(DvmSupport.classNameOf(obj)).append('\n')
        val ref = findRef(base, hash)
        if (ref == null) {
            sb.append("  scope: (resolvable but not in any ref map)\n")
        } else {
            sb.append("  scope: ").append(ref.scope).append('\n')
            sb.append("  refCount: ").append(ref.objRef.refCount).append('\n')
            sb.append("  weak: ").append(ref.objRef.weak).append('\n')
        }
        val preview = DvmSupport.valuePreview(obj)
        sb.append("  preview: ").append(preview ?: "(null)").append('\n')
        if (obj is BaseArray<*>) {
            val len = try {
                obj.length().toString()
            } catch (e: Exception) {
                "(error: " + (e.message ?: e.javaClass.name) + ")"
            }
            sb.append("  arrayLength: ").append(len).append('\n')
        }
        return McpTools.textResult(sb.toString())
    }

    private class FoundRef(@JvmField val scope: String, @JvmField val objRef: BaseVM.ObjRef)

    /** Probe the local, then global, then weak ref maps for the given hash. */
    private fun findRef(base: BaseVM, hash: Int): FoundRef? {
        val local = base.localObjectMap[hash]
        if (local != null) return FoundRef("local", local)
        val global = base.globalObjectMap[hash]
        if (global != null) return FoundRef("global", global)
        val weak = base.weakGlobalObjectMap[hash]
        if (weak != null) return FoundRef("weak", weak)
        return null
    }

    // ---------- dvm_ref_table_stats ----------

    private fun doRefTableStats(): JSONObject {
        val base = DvmSupport.baseVm(vm)
        val sb = StringBuilder()
        sb.append("DVM ref table stats:\n")
        sb.append("  classMap: ").append(base.classMap.size).append(" resolved classes\n")
        sb.append("  local refs: ").append(base.localObjectMap.size).append('\n')
        sb.append("  global refs: ").append(base.globalObjectMap.size).append('\n')
        sb.append("  weak global refs: ").append(base.weakGlobalObjectMap.size).append('\n')
        sb.append("note: resolved classes are also registered as global refs, so the maps overlap.\n\n")
        // histogram of live objects across the three maps, grouped by class name
        val histogram = HashMap<String, Int>()
        var total = 0
        val maps = listOf(base.localObjectMap, base.globalObjectMap, base.weakGlobalObjectMap)
        for (map in maps) {
            for (ref in map.values) {
                val cn = DvmSupport.classNameOf(ref.obj)
                histogram[cn] = (histogram[cn] ?: 0) + 1
                total++
            }
        }
        sb.append("live object histogram by class (").append(total).append(" refs across all maps):\n")
        if (histogram.isEmpty()) {
            sb.append("  (no live objects)\n")
        } else {
            val sorted = histogram.entries.sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            for (e in sorted) {
                sb.append("  ").append(e.value).append("  ").append(e.key).append('\n')
            }
        }
        return McpTools.textResult(sb.toString())
    }

    // ---------- dvm_search_classes ----------

    private fun doSearchClasses(args: JSONObject): JSONObject {
        val query = args.getString("query")
        if (query == null || query.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'query'.")
        }
        val useRegex = args.getBoolean("regex") == true
        val base = DvmSupport.baseVm(vm)
        val regex: Regex? = if (useRegex) {
            try {
                Regex(query)
            } catch (e: Exception) {
                return McpTools.errorResult("Invalid regex '" + query + "': " + (e.message ?: e.javaClass.name))
            }
        } else null
        val matches = ArrayList<String>()
        for (c in base.classMap.values) {
            val slash = c.getClassName()
            val dot = c.getName()
            val hit = if (regex != null) {
                regex.containsMatchIn(slash) || regex.containsMatchIn(dot)
            } else {
                slash.contains(query) || dot.contains(query)
            }
            if (hit) matches.add(slash)
        }
        matches.sort()
        val sb = StringBuilder()
        sb.append("class search for '").append(query).append("'")
                .append(if (useRegex) " (regex)" else " (substring)")
                .append(": ").append(matches.size).append(" match(es)\n")
        for (m in matches) sb.append("  ").append(m).append('\n')
        return McpTools.textResult(sb.toString())
    }

    // ---------- dvm_object_graph ----------

    private class LiveRef(
            @JvmField val scope: String,
            @JvmField val hash: Int,
            @JvmField val objRef: BaseVM.ObjRef)

    private fun doObjectGraph(args: JSONObject): JSONObject {
        val base = DvmSupport.baseVm(vm)
        val groupBy = (args.getString("group_by") ?: "class").trim().toLowerCase()
        if (groupBy != "class" && groupBy != "scope") {
            return McpTools.errorResult("Invalid 'group_by': " + groupBy + " (expected 'class' or 'scope').")
        }
        val limit = args.getInteger("limit") ?: 200

        // collect all live refs
        val all = ArrayList<LiveRef>()
        for ((h, r) in base.localObjectMap) all.add(LiveRef("local", h, r))
        for ((h, r) in base.globalObjectMap) all.add(LiveRef("global", h, r))
        for ((h, r) in base.weakGlobalObjectMap) all.add(LiveRef("weak", h, r))

        val grouped = LinkedHashMap<String, ArrayList<LiveRef>>()
        for (lr in all) {
            val key = if (groupBy == "class") DvmSupport.classNameOf(lr.objRef.obj) else lr.scope
            grouped.getOrPut(key) { ArrayList() }.add(lr)
        }

        val sb = StringBuilder()
        sb.append("object graph (").append(all.size).append(" live refs, grouped by ").append(groupBy)
                .append(", limit=").append(limit).append("):\n")
        var printed = 0
        var truncated = false
        for (key in grouped.keys.sorted()) {
            if (printed >= limit) {
                truncated = true
                break
            }
            val list = grouped[key]!!
            sb.append("[").append(key).append("] ").append(list.size).append(" ref(s)\n")
            for (lr in list) {
                if (printed >= limit) {
                    truncated = true
                    break
                }
                val r = lr.objRef
                sb.append("  0x").append(Integer.toHexString(lr.hash))
                        .append(" scope=").append(lr.scope)
                        .append(" refCount=").append(r.refCount)
                        .append(" weak=").append(r.weak)
                if (groupBy == "scope") {
                    sb.append(" class=").append(DvmSupport.classNameOf(r.obj))
                }
                val preview = DvmSupport.valuePreview(r.obj)
                if (preview != null) sb.append(" = ").append(preview)
                sb.append('\n')
                printed++
            }
        }
        if (truncated) {
            sb.append("... output truncated at limit=").append(limit).append(" (").append(all.size)
                    .append(" total refs). Increase 'limit' to see more.\n")
        }
        return McpTools.textResult(sb.toString())
    }

    // ---------- dvm_find_objects_by_class ----------

    private fun doFindObjectsByClass(args: JSONObject): JSONObject {
        val raw = args.getString("class")
        if (raw == null || raw.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'class'.")
        }
        val target = raw.trim().replace('.', '/')
        val base = DvmSupport.baseVm(vm)
        val sb = StringBuilder()
        sb.append("live objects of class ").append(target).append(":\n")
        var count = 0
        val maps = listOf(
                "local" to base.localObjectMap,
                "global" to base.globalObjectMap,
                "weak" to base.weakGlobalObjectMap)
        for ((scope, map) in maps) {
            for ((h, ref) in map) {
                val cn = DvmSupport.classNameOf(ref.obj).replace('.', '/')
                if (cn == target) {
                    sb.append("  0x").append(Integer.toHexString(h))
                            .append(" scope=").append(scope)
                            .append(" refCount=").append(ref.refCount)
                            .append(" weak=").append(ref.weak)
                    val preview = DvmSupport.valuePreview(ref.obj)
                    if (preview != null) sb.append(" = ").append(preview)
                    sb.append('\n')
                    count++
                }
            }
        }
        if (count == 0) {
            sb.append("  (no live objects of this class)\n")
        } else {
            sb.append("total: ").append(count).append('\n')
        }
        return McpTools.textResult(sb.toString())
    }

    // ---------- dvm_pending_exception ----------

    private fun doPendingException(): JSONObject {
        val base = DvmSupport.baseVm(vm)
        val ex = base.getPendingException()
        if (ex == null) {
            return McpTools.textResult("pending exception: none")
        }
        val sb = StringBuilder()
        sb.append("pending exception:\n")
        sb.append("  class: ").append(DvmSupport.classNameOf(ex)).append('\n')
        sb.append("  value: ").append(DvmSupport.valuePreview(ex) ?: "(null)").append('\n')
        sb.append("note: deleteLocalRefs() clears the pending exception after each JNI call.")
        return McpTools.textResult(sb.toString())
    }
}
