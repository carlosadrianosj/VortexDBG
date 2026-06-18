package com.vortexdbg.linux.android.dvm.mcp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Minimal, dependency-free reader for the Android DEX format. Parses only what the
 * `dvm_dex_surface` MCP tool needs: the string pool, type/proto/method tables and class defs,
 * to list/search classes, method signatures and strings. Not a full DEX loader.
 */
object DexReader {

    class DexInfo(
            @JvmField val classes: List<String>,
            @JvmField val methods: List<String>,
            @JvmField val strings: List<String>)

    private const val HEADER = 0x70

    fun parse(dex: ByteArray): DexInfo {
        if (dex.size < HEADER || dex[0].toInt() != 'd'.code || dex[1].toInt() != 'e'.code || dex[2].toInt() != 'x'.code) {
            throw IllegalArgumentException("Not a DEX file (bad magic)")
        }
        val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)

        val stringIdsSize = buf.getInt(0x38)
        val stringIdsOff = buf.getInt(0x3C)
        val typeIdsSize = buf.getInt(0x40)
        val typeIdsOff = buf.getInt(0x44)
        val protoIdsOff = buf.getInt(0x4C)
        val methodIdsSize = buf.getInt(0x58)
        val methodIdsOff = buf.getInt(0x5C)
        val classDefsSize = buf.getInt(0x60)
        val classDefsOff = buf.getInt(0x64)

        // --- string pool ---
        val strings = ArrayList<String>(stringIdsSize)
        for (i in 0 until stringIdsSize) {
            val dataOff = buf.getInt(stringIdsOff + i * 4)
            strings.add(readMutf8(dex, dataOff))
        }

        // --- types (descriptor index into strings) ---
        val types = IntArray(typeIdsSize)
        for (i in 0 until typeIdsSize) {
            types[i] = buf.getInt(typeIdsOff + i * 4)
        }
        fun typeDesc(typeIdx: Int): String =
                if (typeIdx in 0 until typeIdsSize) stringAt(strings, types[typeIdx]) else "?"

        fun protoSig(protoIdx: Int): String {
            val base = protoIdsOff + protoIdx * 12
            val returnTypeIdx = buf.getInt(base + 4)
            val paramsOff = buf.getInt(base + 8)
            val sb = StringBuilder("(")
            if (paramsOff != 0) {
                val n = buf.getInt(paramsOff)
                for (k in 0 until n) {
                    val ti = buf.getShort(paramsOff + 4 + k * 2).toInt() and 0xFFFF
                    sb.append(typeDesc(ti))
                }
            }
            sb.append(')').append(typeDesc(returnTypeIdx))
            return sb.toString()
        }

        // --- methods: "internalClass : name(args)ret" ---
        val methods = ArrayList<String>(methodIdsSize)
        for (i in 0 until methodIdsSize) {
            val base = methodIdsOff + i * 8
            val classIdx = buf.getShort(base).toInt() and 0xFFFF
            val protoIdx = buf.getShort(base + 2).toInt() and 0xFFFF
            val nameIdx = buf.getInt(base + 4)
            val cls = internalName(typeDesc(classIdx))
            val name = stringAt(strings, nameIdx)
            methods.add("$cls : $name${protoSig(protoIdx)}")
        }

        // --- classes ---
        val classes = ArrayList<String>(classDefsSize)
        for (i in 0 until classDefsSize) {
            val classIdx = buf.getInt(classDefsOff + i * 32)
            classes.add(internalName(typeDesc(classIdx)))
        }

        return DexInfo(classes, methods, strings)
    }

    private fun stringAt(strings: List<String>, idx: Int): String =
            if (idx in strings.indices) strings[idx] else "?"

    /** "Lcom/example/Foo;" -> "com/example/Foo"; primitives/arrays left as-is. */
    private fun internalName(desc: String): String =
            if (desc.length > 2 && desc[0] == 'L' && desc.endsWith(";")) desc.substring(1, desc.length - 1) else desc

    /** Read a MUTF-8 string at [off]: ULEB128 utf16 length, then bytes up to NUL. */
    private fun readMutf8(dex: ByteArray, off: Int): String {
        var p = off
        // skip uleb128 length
        while (true) {
            val b = dex[p++].toInt() and 0xFF
            if (b and 0x80 == 0) break
        }
        val start = p
        while (p < dex.size && dex[p].toInt() != 0) p++
        return String(dex, start, p - start, StandardCharsets.UTF_8)
    }
}
