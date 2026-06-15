package org.telegram.messenger;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.LibraryResolver;
import com.vortexdbg.Module;
import com.vortexdbg.arm.backend.Backend;
import com.vortexdbg.arm.backend.DynarmicFactory;
import com.vortexdbg.arm.backend.KvmFactory;
import com.vortexdbg.arm.backend.Unicorn2Factory;
import com.vortexdbg.linux.android.AndroidEmulatorBuilder;
import com.vortexdbg.linux.android.AndroidResolver;
import com.vortexdbg.linux.android.dvm.DalvikModule;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.VM;
import com.vortexdbg.linux.android.dvm.array.ByteArray;
import com.vortexdbg.linux.android.dvm.jni.ProxyClassFactory;
import com.vortexdbg.memory.Memory;
import com.vortexdbg.utils.Inspector;
import com.vortexdbg.virtualmodule.android.AndroidModule;
import com.vortexdbg.virtualmodule.android.JniGraphics;
import junit.framework.TestCase;

import java.io.File;
import java.io.IOException;

/**
 * mvn test -Dmaven.test.skip=false -Dtest=org.telegram.messenger.Utilities32
 */
public class Utilities32 extends TestCase {

    private static LibraryResolver createLibraryResolver() {
        return new AndroidResolver(23);
    }

    private static AndroidEmulator createARMEmulator() {
        return AndroidEmulatorBuilder
                .for32Bit()
                .setProcessName("org.telegram.messenger")
                .addBackendFactory(new DynarmicFactory(true))
                .addBackendFactory(new KvmFactory(true))
                .addBackendFactory(new Unicorn2Factory(true))
                .build();
    }

    private final AndroidEmulator emulator;
    private final VM vm;

    private final DvmClass cUtilities;

    public Utilities32() {
        emulator = createARMEmulator();
        final Memory memory = emulator.getMemory();
        memory.setLibraryResolver(createLibraryResolver());

        vm = emulator.createDalvikVM();
        vm.setDvmClassFactory(new ProxyClassFactory());
        Module module = new JniGraphics(emulator, vm).register(memory);
        assert module != null;
        new AndroidModule(emulator, vm).register(memory);

        System.out.println("backend=" + emulator.getBackend());
        vm.setVerbose(true);
        File file = new File("src/test/resources/example_binaries/armeabi-v7a/libtmessages.29.so");
        DalvikModule dm = vm.loadLibrary(file.canRead() ? file : new File("unidbg-android/src/test/resources/example_binaries/armeabi-v7a/libtmessages.29.so"), true);
        dm.callJNI_OnLoad(emulator);

        cUtilities = vm.resolveClass("org/telegram/messenger/Utilities");
    }

    private void destroy() throws IOException {
        emulator.close();
        System.out.println("destroy");
    }

    public void test() throws Exception {
        this.aesCbcEncryptionByteArray();
        this.aesCtrDecryptionByteArray();
        this.pbkdf2();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        destroy();
    }

    public static void main(String[] args) throws Exception {
        Utilities32 test = new Utilities32();

        test.aesCbcEncryptionByteArray();
        test.aesCtrDecryptionByteArray();
        test.pbkdf2();

        test.destroy();
    }

    private void aesCbcEncryptionByteArray() {
        long start = System.currentTimeMillis();
        ByteArray data = new ByteArray(vm, new byte[16]);
        byte[] key = new byte[32];
        byte[] iv = new byte[16];
        cUtilities.callStaticJniMethod(emulator, "aesCbcEncryptionByteArray([B[B[BIIII)V", data,
                key,
                iv,
                0, data.length(), 0, 0);
        Inspector.inspect(data.getValue(), "aesCbcEncryptionByteArray offset=" + (System.currentTimeMillis() - start) + "ms");
    }

    private void aesCtrDecryptionByteArray() {
        long start = System.currentTimeMillis();
        ByteArray data = new ByteArray(vm, new byte[16]);
        byte[] key = new byte[32];
        byte[] iv = new byte[16];
        cUtilities.callStaticJniMethod(emulator, "aesCtrDecryptionByteArray([B[B[BIII)V", data,
                key,
                iv,
                0, data.length(), 0);
        Inspector.inspect(data.getValue(), "[" + emulator.getBackend() + "]aesCtrDecryptionByteArray offset=" + (System.currentTimeMillis() - start) + "ms");
    }

    private void pbkdf2() {
        Backend backend = emulator.getBackend();
        byte[] password = "123456".getBytes();
        byte[] salt = new byte[8];
        ByteArray dst = new ByteArray(vm, new byte[64]);
        for (int i = 0; i < 3; i++) {
            long start = System.currentTimeMillis();
            cUtilities.callStaticJniMethod(emulator, "pbkdf2([B[B[BI)V", password,
                    salt,
                    dst, 100000);
            Inspector.inspect(dst.getValue(), String.format("pbkdf2 offset=%dms, allocatedSize=0x%x, residentSize=0x%x", System.currentTimeMillis() - start, backend.getMemAllocatedSize(), backend.getMemResidentSize()));
        }
    }

}
