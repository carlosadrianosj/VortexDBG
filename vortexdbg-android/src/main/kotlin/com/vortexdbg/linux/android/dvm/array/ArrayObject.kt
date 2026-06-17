package com.vortexdbg.linux.android.dvm.array

import com.vortexdbg.linux.android.dvm.Array
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.StringObject
import com.vortexdbg.linux.android.dvm.VM

import java.util.Arrays

class ArrayObject(vararg value: DvmObject<*>?) : BaseArray<kotlin.Array<DvmObject<*>?>>(null, value as kotlin.Array<DvmObject<*>?>), Array<kotlin.Array<DvmObject<*>?>> {

    override fun length(): Int {
        return value.size
    }

    override fun setData(start: Int, data: kotlin.Array<DvmObject<*>?>) {
        System.arraycopy(data, 0, value, start, data.size)
    }

    override fun toString(): String {
        return Arrays.toString(value)
    }

    companion object {
        @JvmStatic
        @Suppress("unused")
        fun newStringArray(vm: VM, vararg strings: String?): ArrayObject {
            val objects = arrayOfNulls<DvmObject<*>?>(strings.size)
            for (i in strings.indices) {
                val str = strings[i]
                if (str != null) {
                    objects[i] = StringObject(vm, str)
                }
            }
            return ArrayObject(*objects)
        }
    }
}
