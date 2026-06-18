package com.vortexdbg.linux.android.dvm

abstract class Hashable {

    protected fun checkJni(vm: BaseVM, dvmClass: DvmClass): Jni {
        val classJni = dvmClass.getJni()
        if (vm.jni == null && classJni == null) {
            throw IllegalStateException("Please vm.setJni(jni)")
        }
        return vm.interceptJni(classJni ?: vm.jni!!)
    }

    override fun hashCode(): Int {
        return super.hashCode()
    }

}
