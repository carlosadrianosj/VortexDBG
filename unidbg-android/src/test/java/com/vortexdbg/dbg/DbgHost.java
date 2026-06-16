package com.vortexdbg.dbg;

import com.vortexdbg.linux.android.dvm.AbstractJni;
import com.vortexdbg.linux.android.dvm.BaseVM;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.VarArg;

/**
 * G (demo) — classe alvo do callback JNI do nativo. O native (libdbg.so) chama
 * {@code DbgHost.onStep(int)} de dentro de {@code compute}; aqui o callback é tratado
 * (no-op) para o native poder retornar.
 */
public class DbgHost extends AbstractJni {

    @Override
    public void callStaticVoidMethod(BaseVM vm, DvmClass dvmClass, String signature, VarArg varArg) {
        if (signature.contains("onStep")) {
            return; // callback native->java tratado
        }
        super.callStaticVoidMethod(vm, dvmClass, signature, varArg);
    }
}
