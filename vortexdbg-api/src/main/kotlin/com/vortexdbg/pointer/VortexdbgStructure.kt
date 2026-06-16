package com.vortexdbg.pointer

import com.vortexdbg.Emulator
import com.vortexdbg.PointerArg
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import org.apache.commons.codec.binary.Hex

import java.lang.reflect.Array
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException

abstract class VortexdbgStructure : Structure, PointerArg {

    private class ByteArrayPointer(private val emulator: Emulator<*>?, private val data: ByteArray) : VortexdbgPointer(emulator, data) {
        override fun share(offset: Long, sz: Long): VortexdbgPointer {
            var sz = sz
            if (offset == 0L) {
                return this
            }
            if (offset > 0 && offset + sz < data.size) {
                if (sz == 0L) {
                    sz = (data.size - offset)
                }
                val tmp = ByteArray(sz.toInt())
                System.arraycopy(data, offset.toInt(), tmp, 0, sz.toInt())
                return ByteArrayPointer(emulator, tmp)
            }
            throw UnsupportedOperationException("offset=0x" + java.lang.Long.toHexString(offset) + ", sz=" + sz)
        }
    }

    protected constructor(emulator: Emulator<*>?, data: ByteArray) : this(ByteArrayPointer(emulator, data))

    protected constructor(data: ByteArray) : this(null, data)

    protected constructor(p: Pointer?) : super(p) {
        checkPointer(p)
    }

    private fun checkPointer(p: Pointer?) {
        if (p == null) {
            throw NullPointerException("p is null")
        }
        if (p !is VortexdbgPointer && !isPlaceholderMemory(p)) {
            throw IllegalArgumentException("p is NOT VortexdbgPointer")
        }
    }

    protected override fun getNativeSize(nativeType: Class<*>, value: Any?): Int {
        if (Pointer::class.java.isAssignableFrom(nativeType)) {
            throw UnsupportedOperationException()
        }

        return super.getNativeSize(nativeType, value)
    }

    protected override fun getNativeAlignment(type: Class<*>, value: Any?, isFirstElement: Boolean): Int {
        if (Pointer::class.java.isAssignableFrom(type)) {
            throw UnsupportedOperationException()
        }

        return super.getNativeAlignment(type, value, isFirstElement)
    }

    private fun isPlaceholderMemory(p: Pointer): Boolean {
        return "native@0x0" == p.toString()
    }

    fun pack() {
        super.write()
    }

    fun unpack() {
        super.read()
    }

    /**
     * @param debug If true, will include a native memory dump of the
     * Structure's backing memory.
     * @return String representation of this object.
     */
    override fun toString(debug: Boolean): String {
        return toString(0, true, debug)
    }

    private fun format(type: Class<*>): String {
        val s = type.getName()
        val dot = s.lastIndexOf(".")
        return s.substring(dot + 1)
    }

    private fun toString(indent: Int, showContents: Boolean, dumpMemory: Boolean): String {
        ensureAllocated()
        val LS = System.getProperty("line.separator")
        var name = format(javaClass) + "(" + getPointer() + ")"
        if (getPointer() !is Memory) {
            name += " (" + size() + " bytes)"
        }
        val prefix = StringBuilder()
        for (idx in 0 until indent) {
            prefix.append("  ")
        }
        var contents = StringBuilder(LS)
        if (!showContents) {
            contents = StringBuilder("...}")
        } else {
            val i = fields().values.iterator()
            while (i.hasNext()) {
                val sf = i.next()
                var value = getFieldValue(sf.field)
                var type = format(sf.type)
                var index = ""
                contents.append(prefix)
                if (sf.type.isArray && value != null) {
                    type = format(sf.type.getComponentType())
                    index = "[" + Array.getLength(value) + "]"
                }
                contents.append(String.format("  %s %s%s@0x%X", type, sf.name, index, sf.offset))
                if (value is VortexdbgStructure) {
                    value = value.toString(indent + 1, value !is Structure.ByReference, dumpMemory)
                }
                contents.append("=")
                if (value is Long) {
                    contents.append(String.format("0x%08X", value))
                } else if (value is Int) {
                    contents.append(String.format("0x%04X", value))
                } else if (value is Short) {
                    contents.append(String.format("0x%02X", value))
                } else if (value is Byte) {
                    contents.append(String.format("0x%01X", value))
                } else if (value is ByteArray) {
                    contents.append(Hex.encodeHexString(value))
                } else {
                    contents.append(java.lang.String.valueOf(value).trim())
                }
                contents.append(LS)
                if (!i.hasNext())
                    contents.append(prefix).append("}")
            }
        }
        if (indent == 0 && dumpMemory) {
            val BYTES_PER_ROW = 4
            contents.append(LS).append("memory dump").append(LS)
            val buf = getPointer().getByteArray(0, size())
            for (i in buf.indices) {
                if ((i % BYTES_PER_ROW) == 0) contents.append("[")
                if (buf[i] >= 0 && buf[i] < 16)
                    contents.append("0")
                contents.append(Integer.toHexString(buf[i].toInt() and 0xff))
                if ((i % BYTES_PER_ROW) == BYTES_PER_ROW - 1 && i < buf.size - 1)
                    contents.append("]").append(LS)
            }
            contents.append("]")
        }
        return name + " {" + contents
    }

    /** Obtain the value currently in the Java field.  Does not read from
     * native memory.
     * @param field field to look up
     * @return current field value (Java-side only)
     */
    private fun getFieldValue(field: Field): Any? {
        try {
            return field.get(this)
        } catch (e: Exception) {
            throw Error("Exception reading field '" + field.getName() + "' in " + javaClass, e)
        }
    }

    /** Return all fields in this structure (ordered).  This represents the
     * layout of the structure, and will be shared among Structures of the
     * same class except when the Structure can have a variable size.
     * NOTE: [.ensureAllocated] *must* be called prior to
     * calling this method.
     * @return [Map] of field names to field representations.
     */
    @Suppress("UNCHECKED_CAST")
    private fun fields(): Map<String, Structure.StructField> {
        try {
            return FIELD_STRUCT_FIELDS.get(this) as Map<String, Structure.StructField>
        } catch (e: IllegalAccessException) {
            throw IllegalStateException(e)
        }
    }

    companion object {
        /** Placeholder pointer to help avoid auto-allocation of memory where a
         * Structure needs a valid pointer but want to avoid actually reading from it.
         */
        private val PLACEHOLDER_MEMORY: Pointer = object : VortexdbgPointer(null, null) {
            override fun share(offset: Long, sz: Long): VortexdbgPointer { return this }
        }

        @JvmStatic
        fun calculateSize(type: Class<out VortexdbgStructure>): Int {
            try {
                val constructor: Constructor<out VortexdbgStructure> = type.getConstructor(Pointer::class.java)
                return constructor.newInstance(PLACEHOLDER_MEMORY).calculateSize(false)
            } catch (e: NoSuchMethodException) {
                throw IllegalStateException(e)
            } catch (e: InstantiationException) {
                throw IllegalStateException(e)
            } catch (e: IllegalAccessException) {
                throw IllegalStateException(e)
            } catch (e: InvocationTargetException) {
                throw IllegalStateException(e)
            }
        }

        private val FIELD_STRUCT_FIELDS: Field

        init {
            try {
                FIELD_STRUCT_FIELDS = Structure::class.java.getDeclaredField("structFields")
                FIELD_STRUCT_FIELDS.setAccessible(true)
            } catch (e: NoSuchFieldException) {
                throw IllegalStateException(e)
            }
        }
    }
}
