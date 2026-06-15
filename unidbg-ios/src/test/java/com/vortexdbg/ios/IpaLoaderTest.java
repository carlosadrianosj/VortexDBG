package com.vortexdbg.ios;

import com.vortexdbg.Emulator;
import com.vortexdbg.Module;
import com.vortexdbg.Symbol;
import com.vortexdbg.arm.backend.Backend;
import com.vortexdbg.arm.backend.DynarmicFactory;
import com.vortexdbg.arm.backend.HypervisorFactory;
import com.vortexdbg.debugger.McpTool;
import com.vortexdbg.debugger.McpToolkit;
import com.vortexdbg.file.ios.DarwinFileIO;
import com.vortexdbg.ios.classdump.ClassDumper;
import com.vortexdbg.ios.classdump.IClassDumper;
import com.vortexdbg.ios.ipa.EmulatorConfigurator;
import com.vortexdbg.ios.ipa.IpaLoader;
import com.vortexdbg.ios.ipa.IpaLoader64;
import com.vortexdbg.ios.ipa.LoadedIpa;
import com.vortexdbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IpaLoaderTest implements EmulatorConfigurator {

    private Emulator<?> emulator;
    private Module module;
    private ExecutorService executorService;

    public void testLoader() throws Exception {
        long start = System.currentTimeMillis();
        File ipa = new File("unidbg-ios/src/test/resources/app/TelegramMessenger-5.11.ipa");
        if (!ipa.canRead()) {
            ipa = new File("src/test/resources/app/TelegramMessenger-5.11.ipa");
        }
        IpaLoader ipaLoader = new IpaLoader64(ipa, new File("target/rootfs/ipa"));
        ipaLoader.addBackendFactory(new HypervisorFactory(true));
        ipaLoader.addBackendFactory(new DynarmicFactory(true));
        LoadedIpa loader = ipaLoader.load(this);
        emulator = loader.getEmulator();
        System.err.println("load offset=" + (System.currentTimeMillis() - start) + "ms");
        loader.callEntry();
        module = loader.getExecutable();

        McpToolkit toolkit = new McpToolkit();
        toolkit.addTool(new McpTool() {
            @Override public String name() { return "dumpClass"; }
            @Override public String description() { return "Dump an ObjC class definition by name"; }
            @Override public String[] paramNames() { return new String[]{"className"}; }
            @Override public void execute(String[] params) {
                String className = params.length > 0 ? params[0] : "AppDelegate";
                IClassDumper classDumper = ClassDumper.getInstance(emulator);
                Backend backend = emulator.getBackend();
                String classDef = classDumper.dumpClass(className);
                System.out.printf("dumpClass(%s):\n%s, allocatedSize=0x%x, residentSize=0x%x, backend=%s%n", className, classDef, backend.getMemAllocatedSize(), backend.getMemResidentSize(), backend);
            }
        }).addTool(new McpTool() {
            @Override public String name() { return "readVersion"; }
            @Override public String description() { return "Read the TelegramCoreVersionString from the executable"; }
            @Override public void execute(String[] params) {
                Symbol sym = module.findSymbolByName("_TelegramCoreVersionString");
                if (sym != null) {
                    Pointer pointer = UnidbgPointer.pointer(emulator, sym.getAddress());
                    if (pointer != null) {
                        System.out.println("_TelegramCoreVersionString=" + pointer.getString(0));
                    }
                    if (emulator.getBackend().isHypervisor()) {
                        final int maxVcpuCount = HypervisorFactory.getMaxVcpuCount();
                        if (executorService == null) {
                            executorService = Executors.newFixedThreadPool(maxVcpuCount - 1);
                        }
                        for(int i = 0; i < maxVcpuCount; i++) {
                            executorService.submit(() -> {
                                try {
                                    synchronized (IpaLoaderTest.this) {
                                        IClassDumper classDumper = ClassDumper.getInstance(emulator);
                                        String objcClass1 = classDumper.dumpClass("NSDate");
                                        System.out.printf("[%s]maxVcpuCount=%d\n%s%n", Thread.currentThread().getName(), HypervisorFactory.getMaxVcpuCount(), objcClass1);
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace(System.err);
                                }
                            });
                        }
                    }
                } else {
                    System.out.println("Symbol _TelegramCoreVersionString not found");
                }
            }
        });
        toolkit.run(emulator.attach());
        if (executorService != null) {
            executorService.shutdown();
        }
        emulator.close();
    }

    public static void main(String[] args) throws Exception {
        IpaLoaderTest test = new IpaLoaderTest();
        test.testLoader();
    }

    @Override
    public void configure(Emulator<DarwinFileIO> emulator, String executableBundlePath, File rootDir, String bundleIdentifier) {
    }

    @Override
    public void onExecutableLoaded(Emulator<DarwinFileIO> emulator, MachOModule executable) {
    }
}
