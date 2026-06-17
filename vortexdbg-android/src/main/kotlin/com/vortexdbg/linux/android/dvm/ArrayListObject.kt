package com.vortexdbg.linux.android.dvm

import java.util.ArrayList

open class ArrayListObject(vm: VM, value: List<out DvmObject<*>>) :
    DvmObject<List<out DvmObject<*>>>(vm.resolveClass("java/util/ArrayList", vm.resolveClass("java/util/List")), value) {

    open fun size(): Int {
        return value.size
    }

    open fun isEmpty(): Boolean {
        return value.isEmpty()
    }

    companion object {
        @JvmStatic
        @Suppress("unused")
        fun newStringList(vm: VM, vararg strings: String?): ArrayListObject {
            val list: MutableList<StringObject> = ArrayList()
            for (str in strings) {
                if (str != null) {
                    list.add(StringObject(vm, str))
                }
            }
            return ArrayListObject(vm, list)
        }
    }
}
