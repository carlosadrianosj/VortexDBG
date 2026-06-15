package com.vortexdbg.ios;

import com.vortexdbg.Emulator;
import com.vortexdbg.LibraryResolver;
import com.vortexdbg.Module;
import com.vortexdbg.Symbol;
import com.vortexdbg.arm.ARMEmulator;
import com.vortexdbg.arm.HookStatus;
import com.vortexdbg.arm.backend.Unicorn2Factory;
import com.vortexdbg.arm.context.RegisterContext;
import com.vortexdbg.file.ios.DarwinFileIO;
import com.vortexdbg.hook.ReplaceCallback;
import com.vortexdbg.hook.substrate.ISubstrate;
import com.vortexdbg.ios.hook.Substrate;
import com.vortexdbg.memory.MemoryBlock;
import com.vortexdbg.pointer.UnidbgPointer;
import com.vortexdbg.utils.Inspector;
import com.sun.jna.Pointer;

import java.io.File;

public class XpcTest extends EmulatorTest<ARMEmulator<DarwinFileIO>> {

    @Override
    protected LibraryResolver createLibraryResolver() {
        return new DarwinResolver();
    }

    @Override
    protected ARMEmulator<DarwinFileIO> createARMEmulator() {
        return DarwinEmulatorBuilder.for32Bit()
                .addBackendFactory(new Unicorn2Factory(true))
                .build();
    }

    private void processXpcNoPie() {
        Module module = emulator.loadLibrary(new File("unidbg-ios/src/test/resources/example_binaries/xpcNP"));

        long start = System.currentTimeMillis();
        int ret = module.callEntry(emulator);
        System.err.println("testXpcNoPie ret=0x" + Integer.toHexString(ret) + ", offset=" + (System.currentTimeMillis() - start) + "ms");

        Symbol objc_getClass = module.findSymbolByName("_objc_getClass");
        assertNotNull(objc_getClass);

        MemoryBlock block = emulator.getMemory().malloc(32, false);
        Symbol snprintf = module.findSymbolByName("_snprintf");
        snprintf.call(emulator, block.getPointer(), 32, "%llu", 0x16dL);
        Inspector.inspect(block.getPointer().getByteArray(0, 32), "snprintf");
    }

    public void testIgnore() {
    }

    @SuppressWarnings("unused")
    private void processXpc() {
        Module module = emulator.loadLibrary(new File("unidbg-ios/src/test/resources/example_binaries/xpc"));

        Symbol malloc_default_zone = module.findSymbolByName("_malloc_default_zone");
        Pointer zone = UnidbgPointer.pointer(emulator, malloc_default_zone.call(emulator).intValue());
        assertNotNull(zone);
        System.err.println("_malloc_default_zone zone=" + zone);

        long start = System.currentTimeMillis();
        int ret = module.callEntry(emulator);
        System.err.println("testXpc ret=0x" + Integer.toHexString(ret) + ", offset=" + (System.currentTimeMillis() - start) + "ms");

        MemoryBlock block = emulator.getMemory().malloc(1, false);
        System.out.println("block=" + block.getPointer());
        block.free();

        ISubstrate substrate = Substrate.getInstance(emulator);
        Module cydiaSubstrate = substrate.getImageByName("/Library/Frameworks/CydiaSubstrate.framework/CydiaSubstrate");
        assertNotNull(cydiaSubstrate);
        assertNotNull(substrate.getImageByName("xpc"));
        assertNull(substrate.getImageByName("not_exists"));

        Module libSystem = substrate.getImageByName("/usr/lib/libSystem.B.dylib");
        assertNotNull(libSystem);

        Symbol _MSFindSymbol = substrate.findSymbol(cydiaSubstrate, "_MSFindSymbol");
        assertNotNull(_MSFindSymbol);
        substrate.hookFunction(_MSFindSymbol, new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator<?> emulator, long originFunction) {
                RegisterContext context = emulator.getContext();
                long image = context.getLongArg(0);
                Pointer symbol = context.getPointerArg(1);
                System.out.println("_MSFindSymbol image=0x" + Long.toHexString(image) + ", symbol=" + symbol.getString(0));
                return HookStatus.RET(emulator, originFunction);
            }
        });

        assertNotNull(substrate.findSymbol(null, "_malloc"));
    }

    public static void main(String[] args) throws Exception {
        XpcTest test = new XpcTest();
        test.setUp();
        test.processXpcNoPie();
        test.tearDown();
    }

}
