package com.vortexdbg.app

import java.io.File

/**
 * Vortex-DBG (A1) CLI — invokes a method on an app class off-device.
 *
 * Example:
 *   java com.vortexdbg.app.VortexCli \
 *     --classes app-classes.jar --android-all android-all.jar \
 *     --class org.cf.crypto.XORCrypt --method encode \
 *     --types String,String --args "hello,k3y"
 *
 * (App classes come from JEB/dex2jar — manual extraction, outside Vortex's scope.)
 */
object VortexCli {

    @JvmStatic
    @Throws(Exception::class)
    fun main(args: Array<String>) {
        val o = parse(args)
        if (o.containsKey("help") || !o.containsKey("class")) {
            usage()
            return
        }

        val b = VortexSession.builder()
        if (o.containsKey("classes")) for (p in o["classes"]!!.split(",")) b.classes(File(p.trim()))
        if (o.containsKey("android-all")) b.androidAll(File(o["android-all"]!!))
        if (o.containsKey("so")) for (p in o["so"]!!.split(",")) b.nativeLib(File(p.trim()))
        if (o.containsKey("sdk")) b.sdk(o["sdk"]!!.toInt())
        if (o.containsKey("arch")) b.arch64("32" != o["arch"])
        if (o.containsKey("verbose")) b.verbose(true)
        if (o.containsKey("jni-onload")) b.callJniOnLoad(true)

        b.open().use { s ->
            val cls = o["class"]!!
            val method = o.getOrDefault("method", "main")
            val types = parseTypes(o["types"])
            val vals = parseArgs(o["args"], types)
            val r = s.invokeStatic(cls, method, types, *vals)
            println("=> " + stringify(r))
        }
    }

    private fun parse(args: Array<String>): Map<String, String> {
        val o = HashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) {
                val key = a.substring(2)
                if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                    o[key] = args[++i]
                } else {
                    o[key] = "true"
                }
            }
            i++
        }
        return o
    }

    private fun parseTypes(spec: String?): Array<Class<*>> {
        if (spec == null || spec.isEmpty()) return arrayOf()
        val parts = spec.split(",")
        val types = arrayOfNulls<Class<*>>(parts.size)
        for (i in parts.indices) types[i] = typeOf(parts[i].trim())
        @Suppress("UNCHECKED_CAST")
        return types as Array<Class<*>>
    }

    private fun typeOf(t: String): Class<*> {
        return when (t) {
            "int" -> Integer.TYPE
            "long" -> java.lang.Long.TYPE
            "boolean" -> java.lang.Boolean.TYPE
            "double" -> java.lang.Double.TYPE
            "float" -> java.lang.Float.TYPE
            "String", "java.lang.String" -> String::class.java
            "int[]" -> IntArray::class.java
            "byte[]" -> ByteArray::class.java
            else ->
                try {
                    Class.forName(t)
                } catch (e: ClassNotFoundException) {
                    throw IllegalArgumentException("tipo desconhecido: $t")
                }
        }
    }

    private fun parseArgs(spec: String?, types: Array<Class<*>>): Array<Any?> {
        if (types.isEmpty()) return arrayOf()
        val parts = if (spec == null) arrayOf() else spec.split(",").toTypedArray()
        if (parts.size != types.size) {
            throw IllegalArgumentException("nº de --args (" + parts.size + ") != nº de --types (" + types.size + ")")
        }
        val vals = arrayOfNulls<Any>(types.size)
        for (i in types.indices) vals[i] = convert(parts[i].trim(), types[i])
        return vals
    }

    private fun convert(s: String, t: Class<*>): Any {
        if (t == Integer.TYPE) return s.toInt()
        if (t == java.lang.Long.TYPE) return s.toLong()
        if (t == java.lang.Boolean.TYPE) return s.toBoolean()
        if (t == java.lang.Double.TYPE) return s.toDouble()
        if (t == java.lang.Float.TYPE) return s.toFloat()
        return s // String e demais
    }

    private fun stringify(r: Any?): String {
        if (r == null) return "null"
        if (r is ByteArray) {
            val sb = StringBuilder()
            for (x in r) sb.append(String.format("%02x", x.toInt() and 0xff))
            return sb.toString()
        }
        return r.toString()
    }

    private fun usage() {
        println(
            "Vortex-DBG CLI\n" +
                "  --classes <jar[,jar]>     .class do app (JEB/dex2jar)\n" +
                "  --android-all <jar>       camada de framework (Robolectric android-all)\n" +
                "  --so <lib[,lib]>          bibliotecas nativas do app\n" +
                "  --sdk <n>                 sdk Android (default 23)\n" +
                "  --arch <32|64>            arquitetura (default 64)\n" +
                "  --class <fqcn>            classe alvo (obrigatório)\n" +
                "  --method <nome>           método estático (default main)\n" +
                "  --types <t,t>             tipos dos parâmetros (int,String,...)\n" +
                "  --args <v,v>              valores dos argumentos\n" +
                "  --verbose --jni-onload"
        )
    }
}
