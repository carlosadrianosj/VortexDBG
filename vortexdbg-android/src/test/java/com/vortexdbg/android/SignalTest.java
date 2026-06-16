package com.vortexdbg.android;

import com.alibaba.fastjson.util.IOUtils;
import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.Emulator;
import com.vortexdbg.Module;
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

public class SignalTest {

    public static void main(String[] args) {
        Logger.getLogger("com.vortexdbg.thread").setLevel(Level.INFO);

        SignalTest test = new SignalTest();
        test.test();
        test.destroy();
    }

    private void destroy() {
        IOUtils.close(emulator);
    }

    private final AndroidEmulator emulator;
    private final Module module;

    private SignalTest() {
        final File executable = new File("vortexdbg-android/src/test/native/android/libs/armeabi-v7a/signal");
        emulator = AndroidEmulatorBuilder
                .for32Bit()
                .addBackendFactory(new Unicorn2Factory(true))
                .build();
        Memory memory = emulator.getMemory();
        emulator.getSyscallHandler().setVerbose(false);
        emulator.getSyscallHandler().setEnableThreadDispatcher(true);
        AndroidResolver resolver = new AndroidResolver(19);
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
    }

    private void test() {
        long start = System.currentTimeMillis();
        boolean ret = emulator.emulateSignal(17);
        int code = module.callEntry(emulator);
        System.out.println("exit code: " + code + ", ret=" + ret + ", backend=" + emulator.getBackend() + ", offset=" + (System.currentTimeMillis() - start) + "ms");
    }

}
