package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.BaseVM
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.mcp.McpTools
import org.apache.commons.codec.binary.Hex
import java.io.File

/**
 * DVM/Java MCP sub-handler for inspecting, diffing and exporting live Dalvik VM state (C6-C7).
 *
 * Tools:
 *  - `dvm_snapshot`: capture the current class set and the three JNI ref maps into a named in-memory snapshot.
 *    Example prompt: "Take a snapshot of the VM state and call it 'before'."
 *  - `dvm_diff`: compare two snapshots (or one snapshot against the current live state) and report added/removed
 *    objects, ref-count changes and class deltas.
 *    Example prompt: "What objects appeared between snapshot 'before' and now?"
 *  - `dvm_export`: serialize a single object, a stored snapshot, or the whole object graph to a file on disk.
 *    Example prompt: "Export the byte[] at handle 0x55 to /tmp/key.bin as raw bytes."
 *
 * Note: local refs are wiped by [BaseVM.deleteLocalRefs] after every JNI call, so the global/weak maps are the
 * meaningful surface for cross-call diffing; the local map only reflects state inside a single in-flight call.
 */
class DvmStateTools(private val emulator: Emulator<*>, private val vm: VM) : DvmSubTools {

    /** A captured object reference: class name, ref count, weak flag and a value preview. */
    private class ObjSnap(
            @JvmField val className: String,
            @JvmField val refCount: Int,
            @JvmField val weak: Boolean,
            @JvmField val preview: String?)

    /** A full snapshot of DVM state at one point in time. */
    private class Snapshot(
            @JvmField val name: String,
            @JvmField val classes: Set<String>,
            @JvmField val local: Map<Int, ObjSnap>,
            @JvmField val global: Map<Int, ObjSnap>,
            @JvmField val weak: Map<Int, ObjSnap>)

    private val snapshots = LinkedHashMap<String, Snapshot>()

    private val toolNames = setOf("dvm_snapshot", "dvm_diff", "dvm_export")

    override fun handles(name: String): Boolean = toolNames.contains(name)

    override fun schemas(): JSONArray {
        val tools = JSONArray()
        tools.add(DvmSupport.schema("dvm_snapshot",
                "[DVM/Java] Capture the current Dalvik VM state into a named in-memory snapshot: the set of resolved " +
                        "class names and, for each of the local/global/weak JNI ref maps, hash -> (class, refCount, weak, value preview). " +
                        "Use dvm_diff later to compare snapshots.",
                DvmSupport.param("name", "Name to store this snapshot under (overwrites an existing one with the same name).")))
        tools.add(DvmSupport.schema("dvm_diff",
                "[DVM/Java] Diff two DVM snapshots: added/removed objects (by JNI hash), ref-count changes, and new/removed " +
                        "classes. Local refs are wiped by deleteLocalRefs() after each JNI call, so the global/weak diffs are the " +
                        "meaningful ones across calls.",
                DvmSupport.param("from", "Name of the earlier snapshot (created with dvm_snapshot)."),
                DvmSupport.param("to", "Name of the later snapshot, or the literal 'now' to capture and diff against current live state.")))
        tools.add(DvmSupport.schema("dvm_export",
                "[DVM/Java] Export DVM data to a file on disk (the only DVM tool that writes to disk). " +
                        "what=object exports one object by JNI hash (raw bytes / hex / json depending on format and value type); " +
                        "what=snapshot exports a stored snapshot as json; what=object_graph exports all three ref maps as json.",
                DvmSupport.param("what", "What to export: 'object', 'snapshot' or 'object_graph'."),
                DvmSupport.param("id", "For 'object': the object's JNI hash (decimal or 0x-hex). For 'snapshot': the snapshot name. Ignored for 'object_graph'."),
                DvmSupport.param("path", "Output file path (will be created/overwritten)."),
                DvmSupport.param("format", "Optional. 'json' (default), 'bin' (raw bytes) or 'hex' (hex text). Mainly relevant for byte-array objects.")))
        return tools
    }

    override fun call(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "dvm_snapshot" -> doSnapshot(args)
                "dvm_diff" -> doDiff(args)
                "dvm_export" -> doExport(args)
                else -> McpTools.errorResult("Unknown DVM state tool: $name")
            }
        } catch (e: Exception) {
            McpTools.errorResult("DVM state tool '$name' failed: " + (e.message ?: e.javaClass.name))
        }
    }

    // ---------- capture ----------

    private fun captureMap(map: Map<Int, BaseVM.ObjRef>): LinkedHashMap<Int, ObjSnap> {
        val out = LinkedHashMap<Int, ObjSnap>()
        for ((hash, ref) in map) {
            val obj = ref.obj
            out[hash] = ObjSnap(DvmSupport.classNameOf(obj), ref.refCount, ref.weak, DvmSupport.valuePreview(obj))
        }
        return out
    }

    private fun capture(name: String): Snapshot {
        val base = DvmSupport.baseVm(vm)
        val classes = LinkedHashSet<String>()
        for (c in base.classMap.values) {
            classes.add(c.getClassName())
        }
        return Snapshot(name, classes, captureMap(base.localObjectMap), captureMap(base.globalObjectMap), captureMap(base.weakGlobalObjectMap))
    }

    private fun doSnapshot(args: JSONObject): JSONObject {
        val name = args.getString("name")
        if (name == null || name.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'name'.")
        }
        val snap = capture(name)
        snapshots[name] = snap
        val sb = StringBuilder()
        sb.append("Captured snapshot '").append(name).append("':\n")
        sb.append("  classes: ").append(snap.classes.size).append('\n')
        sb.append("  local refs: ").append(snap.local.size).append('\n')
        sb.append("  global refs: ").append(snap.global.size).append('\n')
        sb.append("  weak global refs: ").append(snap.weak.size).append('\n')
        sb.append("  (").append(snapshots.size).append(" snapshot(s) stored)")
        return McpTools.textResult(sb.toString())
    }

    // ---------- diff ----------

    private fun diffMap(sb: StringBuilder, label: String, from: Map<Int, ObjSnap>, to: Map<Int, ObjSnap>) {
        val added = ArrayList<Int>()
        val removed = ArrayList<Int>()
        val refChanged = ArrayList<Int>()
        for (h in to.keys) if (!from.containsKey(h)) added.add(h)
        for (h in from.keys) if (!to.containsKey(h)) removed.add(h)
        for (h in to.keys) {
            val a = from[h] ?: continue
            val b = to[h]!!
            if (a.refCount != b.refCount) refChanged.add(h)
        }
        sb.append(label).append(" (from ").append(from.size).append(" -> ").append(to.size).append("):\n")
        if (added.isEmpty() && removed.isEmpty() && refChanged.isEmpty()) {
            sb.append("  (no changes)\n")
            return
        }
        for (h in added) {
            val s = to[h]!!
            sb.append("  + 0x").append(Integer.toHexString(h)).append(" ").append(s.className)
            if (s.preview != null) sb.append(" = ").append(s.preview)
            sb.append('\n')
        }
        for (h in removed) {
            val s = from[h]!!
            sb.append("  - 0x").append(Integer.toHexString(h)).append(" ").append(s.className)
            if (s.preview != null) sb.append(" = ").append(s.preview)
            sb.append('\n')
        }
        for (h in refChanged) {
            val a = from[h]!!
            val b = to[h]!!
            sb.append("  ~ 0x").append(Integer.toHexString(h)).append(" ").append(b.className)
                    .append(" refCount ").append(a.refCount).append(" -> ").append(b.refCount).append('\n')
        }
    }

    private fun diffClasses(sb: StringBuilder, from: Set<String>, to: Set<String>) {
        val added = ArrayList<String>()
        val removed = ArrayList<String>()
        for (c in to) if (!from.contains(c)) added.add(c)
        for (c in from) if (!to.contains(c)) removed.add(c)
        sb.append("classes (from ").append(from.size).append(" -> ").append(to.size).append("):\n")
        if (added.isEmpty() && removed.isEmpty()) {
            sb.append("  (no changes)\n")
            return
        }
        for (c in added) sb.append("  + ").append(c).append('\n')
        for (c in removed) sb.append("  - ").append(c).append('\n')
    }

    private fun doDiff(args: JSONObject): JSONObject {
        val fromName = args.getString("from")
        if (fromName == null || fromName.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'from' (snapshot name).")
        }
        val toName = args.getString("to")
        if (toName == null || toName.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'to' (snapshot name or 'now').")
        }
        val from = snapshots[fromName]
                ?: return McpTools.errorResult("No snapshot named '$fromName'. Stored: " + snapshots.keys.toString())
        val to: Snapshot = if (toName == "now") {
            capture("now")
        } else {
            snapshots[toName]
                    ?: return McpTools.errorResult("No snapshot named '$toName'. Use 'now' or one of: " + snapshots.keys.toString())
        }
        val sb = StringBuilder()
        sb.append("diff '").append(fromName).append("' -> '").append(toName).append("'\n")
        sb.append("note: local refs are wiped by deleteLocalRefs() after each JNI call; global/weak diffs are the meaningful ones.\n\n")
        diffClasses(sb, from.classes, to.classes)
        sb.append('\n')
        diffMap(sb, "global refs", from.global, to.global)
        sb.append('\n')
        diffMap(sb, "weak global refs", from.weak, to.weak)
        sb.append('\n')
        diffMap(sb, "local refs", from.local, to.local)
        return McpTools.textResult(sb.toString())
    }

    // ---------- export ----------

    private fun objSnapToJson(hash: Int, s: ObjSnap): JSONObject {
        val o = JSONObject(true)
        o.put("hash", hash)
        o.put("hashHex", "0x" + Integer.toHexString(hash))
        o.put("class", s.className)
        o.put("refCount", s.refCount)
        o.put("weak", s.weak)
        o.put("preview", s.preview)
        return o
    }

    private fun mapToJson(map: Map<Int, ObjSnap>): JSONArray {
        val arr = JSONArray()
        for ((h, s) in map) arr.add(objSnapToJson(h, s))
        return arr
    }

    private fun snapshotToJson(snap: Snapshot): JSONObject {
        val o = JSONObject(true)
        o.put("name", snap.name)
        val classes = JSONArray()
        for (c in snap.classes) classes.add(c)
        o.put("classes", classes)
        o.put("local", mapToJson(snap.local))
        o.put("global", mapToJson(snap.global))
        o.put("weakGlobal", mapToJson(snap.weak))
        return o
    }

    private fun liveMapToJson(map: Map<Int, BaseVM.ObjRef>): JSONArray {
        val arr = JSONArray()
        for ((h, ref) in map) {
            val o = JSONObject(true)
            o.put("hash", h)
            o.put("hashHex", "0x" + Integer.toHexString(h))
            o.put("class", DvmSupport.classNameOf(ref.obj))
            o.put("refCount", ref.refCount)
            o.put("weak", ref.weak)
            o.put("preview", DvmSupport.valuePreview(ref.obj))
            arr.add(o)
        }
        return arr
    }

    private fun doExport(args: JSONObject): JSONObject {
        val what = args.getString("what")
        if (what == null || what.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'what' (object|snapshot|object_graph).")
        }
        val path = args.getString("path")
        if (path == null || path.trim().isEmpty()) {
            return McpTools.errorResult("Missing required parameter 'path'.")
        }
        val format = (args.getString("format") ?: "json").trim().toLowerCase()
        val file = File(path)
        return when (what) {
            "object" -> exportObject(args, file, format)
            "snapshot" -> exportSnapshot(args, file)
            "object_graph" -> exportObjectGraph(file)
            else -> McpTools.errorResult("Unknown 'what': $what (expected object|snapshot|object_graph).")
        }
    }

    private fun writeResult(file: File, bytes: Long): JSONObject {
        return McpTools.textResult("Wrote " + bytes + " bytes to " + file.absolutePath)
    }

    private fun exportObject(args: JSONObject, file: File, format: String): JSONObject {
        val hash = DvmSupport.parseHashInt(args.getString("id"))
                ?: return McpTools.errorResult("For what=object, 'id' must be the object's JNI hash (decimal or 0x-hex).")
        val obj: DvmObject<*> = DvmSupport.baseVm(vm).getObject(hash)
                ?: return McpTools.errorResult("No object found for hash 0x" + Integer.toHexString(hash) + ".")
        val className = DvmSupport.classNameOf(obj)
        val value = try {
            obj.getValue()
        } catch (e: Exception) {
            null
        }
        try {
            if (value is kotlin.ByteArray) {
                when (format) {
                    "bin" -> {
                        file.writeBytes(value)
                        return writeResult(file, value.size.toLong())
                    }
                    "hex" -> {
                        val text = Hex.encodeHexString(value)
                        file.writeText(text)
                        return writeResult(file, text.toByteArray().size.toLong())
                    }
                    else -> {
                        val o = JSONObject(true)
                        o.put("class", className)
                        o.put("hash", hash)
                        o.put("length", value.size)
                        o.put("hex", Hex.encodeHexString(value))
                        val text = o.toJSONString()
                        file.writeText(text)
                        return writeResult(file, text.toByteArray().size.toLong())
                    }
                }
            }
            val o = JSONObject(true)
            o.put("class", className)
            o.put("hash", hash)
            o.put("value", value?.toString())
            val text = o.toJSONString()
            file.writeText(text)
            return writeResult(file, text.toByteArray().size.toLong())
        } catch (e: java.io.IOException) {
            return McpTools.errorResult("Failed to write '" + file.absolutePath + "': " + (e.message ?: e.javaClass.name))
        }
    }

    private fun exportSnapshot(args: JSONObject, file: File): JSONObject {
        val id = args.getString("id")
        if (id == null || id.trim().isEmpty()) {
            return McpTools.errorResult("For what=snapshot, 'id' must be a stored snapshot name.")
        }
        val snap = snapshots[id]
                ?: return McpTools.errorResult("No snapshot named '$id'. Stored: " + snapshots.keys.toString())
        return try {
            val text = snapshotToJson(snap).toJSONString()
            file.writeText(text)
            writeResult(file, text.toByteArray().size.toLong())
        } catch (e: java.io.IOException) {
            McpTools.errorResult("Failed to write '" + file.absolutePath + "': " + (e.message ?: e.javaClass.name))
        }
    }

    private fun exportObjectGraph(file: File): JSONObject {
        val base = DvmSupport.baseVm(vm)
        return try {
            val o = JSONObject(true)
            o.put("local", liveMapToJson(base.localObjectMap))
            o.put("global", liveMapToJson(base.globalObjectMap))
            o.put("weakGlobal", liveMapToJson(base.weakGlobalObjectMap))
            val text = o.toJSONString()
            file.writeText(text)
            writeResult(file, text.toByteArray().size.toLong())
        } catch (e: java.io.IOException) {
            McpTools.errorResult("Failed to write '" + file.absolutePath + "': " + (e.message ?: e.javaClass.name))
        }
    }
}
