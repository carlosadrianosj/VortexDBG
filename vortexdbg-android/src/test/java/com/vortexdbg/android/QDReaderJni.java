package com.vortexdbg.android;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.Emulator;
import com.vortexdbg.LibraryResolver;
import com.vortexdbg.Module;
import com.vortexdbg.ModuleListener;
import com.vortexdbg.Symbol;
import com.vortexdbg.arm.HookStatus;
import com.vortexdbg.arm.backend.DynarmicFactory;
import com.vortexdbg.hook.ReplaceCallback;
import com.vortexdbg.hook.xhook.IxHook;
import com.vortexdbg.linux.android.AndroidEmulatorBuilder;
import com.vortexdbg.linux.android.AndroidResolver;
import com.vortexdbg.linux.android.XHookImpl;
import com.vortexdbg.linux.android.dvm.DalvikModule;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.StringObject;
import com.vortexdbg.linux.android.dvm.VM;
import com.vortexdbg.linux.android.dvm.array.ByteArray;
import com.vortexdbg.linux.android.dvm.jni.ProxyClassFactory;
import com.vortexdbg.memory.Memory;
import com.vortexdbg.utils.Inspector;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.DESedeKeySpec;
import javax.crypto.spec.IvParameterSpec;
import java.io.File;
import java.io.IOException;

public class QDReaderJni implements ModuleListener {

    private static final int SDK = 23;

    private static LibraryResolver createLibraryResolver() {
        return new AndroidResolver(SDK);
    }

    private static AndroidEmulator createARMEmulator() {
        return AndroidEmulatorBuilder.for32Bit()
                .setProcessName("a.d.c")
                .addBackendFactory(new DynarmicFactory(true))
                .build();
    }

    private final AndroidEmulator emulator;
    private final VM vm;

    private final DvmClass d;

    private QDReaderJni() {
        emulator = createARMEmulator();
        final Memory memory = emulator.getMemory();
        memory.setLibraryResolver(createLibraryResolver());
        memory.addModuleListener(this);

        vm = emulator.createDalvikVM();
        vm.setDvmClassFactory(new ProxyClassFactory());
        vm.setVerbose(true);
        DalvikModule dm = vm.loadLibrary(new File("vortexdbg-android/src/test/resources/example_binaries/armeabi-v7a/libd-lib.so"), false);
        dm.callJNI_OnLoad(emulator);

        d = vm.resolveClass("a/d");
    }

    private void destroy() throws IOException {
        emulator.close();
        System.out.println("destroy");
    }

    public static void main(String[] args) throws Exception {
        QDReaderJni test = new QDReaderJni();

        test.c();

        test.destroy();
    }

    @Override
    public void onLoaded(Emulator<?> emulator, Module module) {
        if ("libcrypto.so".equals(module.name)) {
            Symbol DES_set_key = module.findSymbolByName("DES_set_key", false);
            Symbol DES_set_key_unchecked = module.findSymbolByName("DES_set_key_unchecked", false);
            if (DES_set_key_unchecked == null && DES_set_key != null) {
                module.registerSymbol("DES_set_key_unchecked", DES_set_key.getAddress());
            }
        }
    }

    private void c() throws Exception {
        IxHook xHook = XHookImpl.getInstance(emulator);
        xHook.register("libd-lib.so", "free", new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator<?> emulator, long originFunction) {
                return HookStatus.LR(emulator, 0);
            }
        });

        xHook.refresh();

        final String data = "359250054370919||1551086094";
        long start = System.currentTimeMillis();
//        emulator.traceCode();
        ByteArray array = d.callStaticJniMethodObject(emulator, "c(Ljava/lang/String;)[B", new StringObject(vm, data));
        Inspector.inspect(array.getValue(), "c offset=" + (System.currentTimeMillis() - start) + "ms");

        final String key = "sewxf03hhz3ew9qcCXMHiDMk";
        final String iv = "sh331nt1";

        Cipher cipher = Cipher.getInstance("DESede/CBC/PKCS5Padding");
        DESedeKeySpec keySpec = new DESedeKeySpec(key.getBytes());
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("DESede");
        SecretKey secretKey = keyFactory.generateSecret(keySpec);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv.getBytes()));
        byte[] encrypted = cipher.doFinal(data.getBytes());
        Inspector.inspect(encrypted, "Encrypted");

        cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv.getBytes()));
        byte[] decrypted = cipher.doFinal(array.getValue());
        Inspector.inspect(decrypted, "Decrypted");
    }

}
