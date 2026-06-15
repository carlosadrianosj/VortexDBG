package com.vortexdbg.ios;

import com.vortexdbg.Emulator;
import com.vortexdbg.LibraryResolver;
import com.vortexdbg.arm.ARMEmulator;
import com.vortexdbg.arm.HookStatus;
import com.vortexdbg.arm.backend.KvmFactory;
import com.vortexdbg.arm.backend.Unicorn2Factory;
import com.vortexdbg.file.ios.DarwinFileIO;
import com.vortexdbg.hook.HookContext;
import com.vortexdbg.hook.ReplaceCallback;
import com.vortexdbg.hook.substrate.ISubstrate;
import com.vortexdbg.ios.classdump.ClassDumper;
import com.vortexdbg.ios.classdump.IClassDumper;
import com.vortexdbg.ios.hook.Substrate;
import com.vortexdbg.ios.objc.ObjC;
import com.vortexdbg.ios.struct.objc.ObjcClass;
import com.vortexdbg.ios.struct.objc.ObjcObject;
import com.sun.jna.Pointer;

import java.io.File;

public class ClassDumpTest extends EmulatorTest<ARMEmulator<DarwinFileIO>> {

    @Override
    protected LibraryResolver createLibraryResolver() {
        return new DarwinResolver();
    }

    @Override
    protected ARMEmulator<DarwinFileIO> createARMEmulator() {
        return DarwinEmulatorBuilder.for32Bit()
                .setRootDir(new File("target/rootfs/classdump"))
                .addBackendFactory(new KvmFactory(true))
                .addBackendFactory(new Unicorn2Factory(true))
                .build();
    }

    public void testClassDump() {
        MachOLoader loader = (MachOLoader) emulator.getMemory();
        loader.setObjcRuntime(true);
        IClassDumper classDumper = ClassDumper.getInstance(emulator);
        ISubstrate substrate = Substrate.getInstance(emulator);

        ObjC objc = ObjC.getInstance(emulator);
        ObjcClass oClassDump = objc.getClass("ClassDump");
        assertNotNull(oClassDump);
        substrate.hookMessageEx(oClassDump.getMeta(), objc.registerName("my_dump_class:"), new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator<?> emulator, HookContext context, long originFunction) {
                Pointer id = context.getPointerArg(0);
                Pointer SEL = context.getPointerArg(1);
                Pointer name = context.getPointerArg(2);
                String className = name.getString(0);
                context.push(className);
                if (!"NSLocale".equals(className)) {
                    return HookStatus.RET(emulator, originFunction);
                }

                ObjcObject obj = ObjcObject.create(emulator, id);
                System.err.println("my_dump_class id=" + id + ", SEL=" + SEL + ", name=" + className + ", className=" + obj.getObjClass().getName());
                name.setString(0, "NSDate");
                return HookStatus.RET(emulator, originFunction);
            }
            @Override
            public void postCall(Emulator<?> emulator, HookContext context) {
                System.err.println("postCall className=" + context.pop());
            }
        }, true);

        String objcClass = classDumper.dumpClass("NSLocale");
        System.out.println(objcClass);

        assertTrue(oClassDump.getMeta().isMetaClass());
        System.out.println("[" + emulator.getBackend() + "]className=" + oClassDump.getName() + ", metaClassName=" + oClassDump.getMeta().getName());

        ObjcObject str = oClassDump.callObjc("my_dump_class:", "NSDictionary");
        System.out.println(str.getDescription());

        classDumper.searchClass("ClassD");
    }

    public static void main(String[] args) throws Exception {
        ClassDumpTest test = new ClassDumpTest();
        test.setUp();
        test.testClassDump();
        test.tearDown();
    }

}
