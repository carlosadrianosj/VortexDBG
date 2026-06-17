package com.vortexdbg.linux.android.dvm

import java.util.ArrayList

abstract class VarArg protected constructor(private val vm: BaseVM, protected val method: DvmMethod) {

    @JvmField
    val args: MutableList<Any?>

    protected var shorties: kotlin.Array<Shorty>

    init {
        this.shorties = method.decodeArgsShorty()
        this.args = ArrayList(shorties.size)
    }

    /**
     * @param index 0 based
     */
    fun <T : DvmObject<*>?> getObjectArg(index: Int): T {
        val hash = getIntArg(index)
        return vm.getObject(hash)
    }

    /**
     * @param index 0 based
     */
    fun getIntArg(index: Int): Int {
        return args[index] as Int
    }

    /**
     * @param index 0 based
     */
    fun getLongArg(index: Int): Long {
        return args[index] as Long
    }

    /**
     * @param index 0 based
     */
    fun getFloatArg(index: Int): Float {
        return args[index] as Float
    }

    /**
     * @param index 0 based
     */
    fun getDoubleArg(index: Int): Double {
        return args[index] as Double
    }

    fun formatArgs(): String {
        val shorties = method.decodeArgsShorty()
        val format: MutableList<String> = ArrayList(shorties.size)
        val args: MutableList<Any?> = ArrayList(shorties.size)
        for (i in shorties.indices) {
            val shorty = shorties[i]
            when (shorty.getType()) {
                'B' -> {
                    format.add("%s")
                    args.add(getIntArg(i).toByte())
                }
                'C' -> {
                    format.add("%c")
                    args.add(getIntArg(i).toChar())
                }
                'I' -> {
                    format.add("0x%x")
                    args.add(getIntArg(i))
                }
                'S' -> {
                    format.add("%s")
                    args.add(getIntArg(i).toShort())
                }
                'Z' -> {
                    format.add("%s")
                    args.add(BaseVM.valueOf(getIntArg(i)))
                }
                'F' -> {
                    format.add("%fF")
                    args.add(getFloatArg(i))
                }
                'L' -> {
                    format.add("%s")
                    args.add(getObjectArg<DvmObject<*>?>(i))
                }
                'D' -> {
                    format.add("%sD")
                    args.add(getDoubleArg(i))
                }
                'J' -> {
                    format.add("0x%xL")
                    args.add(getLongArg(i))
                }
                else -> throw IllegalStateException("c=" + shorty.getType())
            }
        }
        val sb = StringBuilder()
        if (format.isNotEmpty()) {
            sb.append(format.removeAt(0))
        }
        for (str in format) {
            sb.append(", ").append(str)
        }
        return String.format(sb.toString(), *args.toTypedArray())
    }
}
