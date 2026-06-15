package com.vortexdbg.ios.patch;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.Arm64Svc;
import com.vortexdbg.arm.context.RegisterContext;
import com.vortexdbg.hook.HookListener;
import com.vortexdbg.memory.SvcMemory;

public class OldObjcPatcher implements HookListener {

    private long _objc_opt_self;

    @Override
    public long hook(SvcMemory svcMemory, String libraryName, String symbolName, long old) {
        if ("_objc_opt_self".equals(symbolName)) {
            if (_objc_opt_self == 0) {
                _objc_opt_self = svcMemory.registerSvc(new Arm64Svc("objc_opt_self") {
                    @Override
                    public long handle(Emulator<?> emulator) {
                        RegisterContext context = emulator.getContext();
                        return context.getLongArg(0);
                    }
                }).peer;
            }
            return _objc_opt_self;
        }
        return 0;
    }
}
