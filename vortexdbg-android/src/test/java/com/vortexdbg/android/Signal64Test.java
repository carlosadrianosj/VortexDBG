package com.vortexdbg.android;

import com.alibaba.fastjson.util.IOUtils;
import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.Emulator;
import com.vortexdbg.Module;
import com.vortexdbg.arm.backend.Backend;
import com.vortexdbg.arm.backend.Unicorn2Factory;
import com.vortexdbg.debugger.Debugger;
import com.vortexdbg.debugger.FunctionCallListener;
import com.vortexdbg.linux.android.AndroidEmulatorBuilder;
import com.vortexdbg.linux.android.AndroidResolver;
import com.vortexdbg.memory.Memory;
import com.vortexdbg.pointer.VortexdbgPointer;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.io.File;

public class Signal64Test {

    public static void main(String[] args) {
        Logger.getLogger("com.vortexdbg.thread").setLevel(Level.INFO);

        Signal64Test test = new Signal64Test();
        test.test();
        test.destroy();
    }

    private void destroy() {
        IOUtils.close(emulator);
    }

    private final AndroidEmulator emulator;
    private final Module module;

    private Signal64Test() {
        final File executable = new File("vortexdbg-android/src/test/native/android/libs/arm64-v8a/signal");
        emulator = AndroidEmulatorBuilder
                .for64Bit()
                .addBackendFactory(new Unicorn2Factory(true))
                .build();
        Memory memory = emulator.getMemory();
        emulator.getSyscallHandler().setVerbose(false);
        emulator.getSyscallHandler().setEnableThreadDispatcher(true);
        AndroidResolver resolver = new AndroidResolver(23);
        memory.setLibraryResolver(resolver);

        Debugger debugger = emulator.attach();
        module = emulator.loadLibrary(executable, true);
        debugger.traceFunctionCall(module, new FunctionCallListener() {
            @Override
            public void onCall(Emulator<?> emulator, long callerAddress, long functionAddress) {
            }
            @Override
            public void postCall(Emulator<?> emulator, long callerAddress, long functionAddress, Number[] args) {
                System.out.println("onCallFinish caller=" + VortexdbgPointer.pointer(emulator, callerAddress) + ", function=" + VortexdbgPointer.pointer(emulator, functionAddress));
            }
        });
        emulator.traceCode(module.base, module.base + module.size);
        Backend backend = emulator.getBackend();
        backend.removeJitCodeCache(module.base, module.base + module.size);
    }

    private void test() {
        long start = System.currentTimeMillis();
        boolean ret = emulator.emulateSignal(29);
        int code = module.callEntry(emulator);
        System.out.println("exit code: " + code + ", ret=" + ret + ", backend=" + emulator.getBackend() + ", offset=" + (System.currentTimeMillis() - start) + "ms");
    }

}
