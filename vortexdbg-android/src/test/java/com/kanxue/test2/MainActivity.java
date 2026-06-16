package com.kanxue.test2;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.LibraryResolver;
import com.vortexdbg.arm.backend.DynarmicFactory;
import com.vortexdbg.linux.android.AndroidEmulatorBuilder;
import com.vortexdbg.linux.android.AndroidResolver;
import com.vortexdbg.linux.android.dvm.DalvikModule;
import com.vortexdbg.linux.android.dvm.DvmObject;
import com.vortexdbg.linux.android.dvm.VM;
import com.vortexdbg.linux.android.dvm.jni.ProxyDvmObject;
import com.vortexdbg.memory.Memory;

import java.io.File;

/**
 * <a href="https://bbs.pediy.com/thread-263345.htm">CrackMe</a>
 */
public class MainActivity {

    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        MainActivity mainActivity = new MainActivity();
        System.out.println("load offset=" + (System.currentTimeMillis() - start) + "ms");
        mainActivity.crack();
    }

    private final AndroidEmulator emulator;
    private final VM vm;

    private MainActivity() {
        emulator = AndroidEmulatorBuilder
                .for32Bit()
                .addBackendFactory(new DynarmicFactory(true))
                .build();
        Memory memory = emulator.getMemory();
        LibraryResolver resolver = new AndroidResolver(23);
        memory.setLibraryResolver(resolver);

        vm = emulator.createDalvikVM();
        vm.setVerbose(false);
        DalvikModule dm = vm.loadLibrary(new File("vortexdbg-android/src/test/resources/example_binaries/armeabi-v7a/libnative-lib.so"), false);
        dm.callJNI_OnLoad(emulator);
    }

    private static final char[] LETTERS = {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z'
    };

    private void crack() {
        DvmObject<?> obj = ProxyDvmObject.createObject(vm, this);
        long start = System.currentTimeMillis();
        for (char a : LETTERS) {
            for (char b : LETTERS) {
                for (char c : LETTERS) {
                    String str = "" + a + b + c;
                    boolean success = obj.callJniMethodBoolean(emulator, "jnitest(Ljava/lang/String;)Z", str);
                    if (success) {
                        System.out.println("Found: " + str + ", off=" + (System.currentTimeMillis() - start) + "ms");
                        return;
                    }
                }
            }
        }
    }
}
