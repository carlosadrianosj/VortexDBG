package com.vortexdbg.linux.android.dvm

interface DvmClassFactory {

    fun createClass(vm: BaseVM, className: String, superClass: DvmClass?, interfaceClasses: kotlin.Array<DvmClass>?): DvmClass?

}
