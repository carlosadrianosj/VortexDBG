package com.vortexdbg.linux.android.dvm.mcp

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.BaseVM
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmField
import com.vortexdbg.linux.android.dvm.DvmMethod
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.Jni
import com.vortexdbg.linux.android.dvm.JniFunction
import com.vortexdbg.linux.android.dvm.JniInterceptor
import com.vortexdbg.linux.android.dvm.StringObject
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.linux.android.dvm.VarArg
import com.vortexdbg.mcp.McpTools

/**
 * DVM/Java MCP sub-handler: environment spoofing (C5).
 *
 * Installs a [Jni] interceptor (via [BaseVM.setJni], wrapping the current jni as fallback through
 * [JniFunction]) that returns canned values for a handful of well-known `android.os.Build` fields and
 * identity calls (getSerial, currentTimeMillis, etc.). Everything not matched is delegated to the
 * original jni automatically by [JniFunction]. This rides on the JniFunction/setJni hook and therefore
 * only affects native -> Java callbacks made during emulated calls; it does not patch the host JVM.
 *
 * Tool:
 *  - `dvm_spoof_env`: seed canned device-identity values (from a preset + overrides) so the .so reads a
 *    fake Build/serial/ANDROID_ID/etc.; enable=false restores the real jni.
 *    Example prompt: "Make the app think it's running on a Google Pixel, then override the serial to 'TEST123'."
 */
class DvmSpoofTools(private val emulator: Emulator<*>, private val vm: VM) : DvmSubTools {

    /** Registered jni interceptor (null when disabled). */
    private var interceptor: JniInterceptor? = null
    /** Last applied value map (read live by the interceptor). */
    private var currentValues: Map<String, String> = emptyMap()

    override fun handles(name: String): Boolean = name == "dvm_spoof_env"

    override fun schemas(): JSONArray {
        val tools = JSONArray()
        tools.add(DvmSupport.schema("dvm_spoof_env",
                "[DVM/Java] Spoof device identity returned through the JNI bridge by installing a Jni " +
                        "interceptor (BaseVM.setJni wrapping the current jni as fallback via JniFunction). " +
                        "Returns canned values for android/os/Build fields (MODEL, MANUFACTURER, BRAND, " +
                        "FINGERPRINT), Build.getSerial(), Settings ANDROID_ID, the app packageName and " +
                        "System.currentTimeMillis(); unmatched signatures fall through to the real jni. " +
                        "This only affects native->Java callbacks made during emulated calls (it does not " +
                        "patch the host JVM). Use a preset, then override individual keys as needed. " +
                        "Set enable=false to restore the original jni.",
                DvmSupport.param("preset", "Optional. One of: pixel | samsung | generic. Seeds the value table."),
                DvmSupport.param("overrides", "Optional. JSON object map of key->value merged over the preset. " +
                        "Keys: Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.FINGERPRINT, getSerial, " +
                        "ANDROID_ID, packageName, currentTimeMillis (millis as a number string).", "object"),
                DvmSupport.param("enable", "Optional. true (default) to install/refresh the interceptor, " +
                        "false to restore the original jni.")))
        return tools
    }

    override fun call(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "dvm_spoof_env" -> spoofEnv(args)
                else -> McpTools.errorResult("DvmSpoofTools cannot handle tool: $name")
            }
        } catch (e: Exception) {
            McpTools.errorResult("dvm_spoof_env failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun spoofEnv(args: JSONObject): JSONObject {
        // Spoofing only mutates the jni hook; it must not race a live emulation.
        if (emulator.isRunning()) {
            return McpTools.errorResult("Emulator is running; stop it before changing the spoof jni hook.")
        }
        val base = DvmSupport.baseVm(vm)

        val enable = parseBool(args.get("enable"), true)

        if (!enable) {
            if (interceptor == null) {
                return McpTools.textResult("Spoofing already disabled; nothing to restore.")
            }
            interceptor?.let { base.jniInterceptors.remove(it) }
            interceptor = null
            currentValues = emptyMap()
            return McpTools.textResult("Spoofing disabled; interceptor removed.")
        }

        // Build the value table: preset defaults merged with explicit overrides.
        val preset = args.getString("preset")
        val values = LinkedHashMap<String, String>()
        values.putAll(presetTable(preset))
        applyOverrides(values, args.get("overrides"))
        currentValues = values

        // Register the interceptor once; it reads currentValues live on each call.
        if (interceptor == null) {
            val itc = JniInterceptor { fallback -> SpoofJni(fallback) }
            base.jniInterceptors.add(itc)
            interceptor = itc
        }

        val sb = StringBuilder()
        sb.append("Spoofing enabled")
        if (preset != null) sb.append(" (preset=").append(preset).append(")")
        sb.append(". ").append(values.size).append(" key(s) now spoofed via the JNI hook:\n")
        for ((k, v) in values) {
            sb.append("  ").append(k).append(" = ").append(v).append("\n")
        }
        sb.append("Note: only affects native->Java callbacks during emulated calls. ")
        sb.append("Call again with enable=false to restore the original jni.")
        return McpTools.textResult(sb.toString().trimEnd())
    }

    // ---------- value tables ----------

    private fun presetTable(preset: String?): Map<String, String> {
        return when (preset?.trim()?.lowercase()) {
            "pixel" -> linkedMapOf(
                    "Build.MODEL" to "Pixel 7",
                    "Build.MANUFACTURER" to "Google",
                    "Build.BRAND" to "google",
                    "Build.FINGERPRINT" to "google/panther/panther:13/TQ3A.230805.001/10316531:user/release-keys",
                    "getSerial" to "PIXEL000SERIAL01",
                    "ANDROID_ID" to "a1b2c3d4e5f60718")
            "samsung" -> linkedMapOf(
                    "Build.MODEL" to "SM-G991B",
                    "Build.MANUFACTURER" to "samsung",
                    "Build.BRAND" to "samsung",
                    "Build.FINGERPRINT" to "samsung/o1sxxx/o1s:13/TP1A.220624.014/G991BXXU5DVK1:user/release-keys",
                    "getSerial" to "SAMSUNG00SERIAL1",
                    "ANDROID_ID" to "9f8e7d6c5b4a3021")
            "generic", null, "" -> linkedMapOf(
                    "Build.MODEL" to "Android SDK built for arm64",
                    "Build.MANUFACTURER" to "unknown",
                    "Build.BRAND" to "generic",
                    "Build.FINGERPRINT" to "generic/sdk_gphone_arm64/emulator:13/TE1A.220922.034/9101199:userdebug/test-keys",
                    "getSerial" to "GENERIC00SERIAL1",
                    "ANDROID_ID" to "0123456789abcdef")
            else -> throw IllegalArgumentException("Unknown preset '$preset' (expected pixel|samsung|generic)")
        }
    }

    private fun applyOverrides(target: LinkedHashMap<String, String>, raw: Any?) {
        if (raw == null) return
        val obj: JSONObject = when (raw) {
            is JSONObject -> raw
            is String -> if (raw.isBlank()) return else JSONObject.parseObject(raw)
            else -> JSONObject.parseObject(raw.toString())
        }
        for (key in obj.keys) {
            val v = obj.get(key)
            if (v != null) target[key] = v.toString()
        }
    }

    private fun parseBool(raw: Any?, default: Boolean): Boolean {
        if (raw == null) return default
        if (raw is Boolean) return raw
        val s = raw.toString().trim().lowercase()
        return when (s) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> default
        }
    }

    // ---------- the interceptor ----------

    /**
     * Wraps the previous [Jni] and overrides only the handful of methods that surface device identity:
     * static object fields (Build.*), static object methods (getSerial / ANDROID_ID / packageName),
     * the equivalent instance object methods, and System.currentTimeMillis(). All other Jni calls are
     * delegated to [fallback] automatically by [JniFunction].
     */
    private inner class SpoofJni(private val fallback: Jni) : Jni by fallback {

        private val values: Map<String, String> get() = currentValues

        override fun getStaticObjectField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): DvmObject<*> {
            spoofForField(dvmField.getSignature())?.let { return StringObject(vm, it) }
            return fallback.getStaticObjectField(vm, dvmClass, dvmField)
        }

        override fun callStaticObjectMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> {
            spoofForMethod(dvmMethod.getSignature())?.let { return StringObject(vm, it) }
            return fallback.callStaticObjectMethod(vm, dvmClass, dvmMethod, varArg)
        }

        override fun callObjectMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> {
            spoofForMethod(dvmMethod.getSignature())?.let { return StringObject(vm, it) }
            return fallback.callObjectMethod(vm, dvmObject, dvmMethod, varArg)
        }

        override fun callStaticLongMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Long {
            spoofedMillis(dvmMethod.getSignature())?.let { return it }
            return fallback.callStaticLongMethod(vm, dvmClass, dvmMethod, varArg)
        }

        override fun callLongMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Long {
            spoofedMillis(dvmMethod.getSignature())?.let { return it }
            return fallback.callLongMethod(vm, dvmObject, dvmMethod, varArg)
        }

        /** Resolve a spoof string for a Build.* static field signature; null = not spoofed. */
        private fun spoofForField(signature: String): String? {
            // Match leniently on the field name embedded in the signature, e.g.
            // "android/os/Build->MODEL:Ljava/lang/String;".
            if (signature.contains("MODEL")) return values["Build.MODEL"]
            if (signature.contains("MANUFACTURER")) return values["Build.MANUFACTURER"]
            if (signature.contains("FINGERPRINT")) return values["Build.FINGERPRINT"]
            if (signature.contains("BRAND")) return values["Build.BRAND"]
            return null
        }

        /** Resolve a spoof string for an object-returning method signature; null = not spoofed. */
        private fun spoofForMethod(signature: String): String? {
            if (signature.contains("getSerial")) return values["getSerial"]
            // Settings.Secure.getString(...) is commonly used to read ANDROID_ID.
            if (signature.contains("ANDROID_ID") || signature.contains("getAndroidId")) return values["ANDROID_ID"]
            if (signature.contains("getPackageName")) return values["packageName"]
            return null
        }

        /** Resolve a spoofed long for currentTimeMillis()J; null = not spoofed. */
        private fun spoofedMillis(signature: String): Long? {
            if (!signature.contains("currentTimeMillis")) return null
            val raw = values["currentTimeMillis"] ?: return null
            return try {
                DvmSupport.parseLong(raw)
            } catch (e: Exception) {
                null
            }
        }
    }
}
