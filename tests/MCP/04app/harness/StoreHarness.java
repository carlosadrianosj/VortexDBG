package com.vortexdbg.mcpstore;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.app.VortexClassLoader;
import com.vortexdbg.arm.backend.BackendFactory;
import com.vortexdbg.arm.backend.Unicorn2Factory;
import com.vortexdbg.debugger.Debugger;
import com.vortexdbg.debugger.McpTool;
import com.vortexdbg.debugger.McpToolkit;
import com.vortexdbg.linux.android.AndroidARM64Emulator;
import com.vortexdbg.linux.android.AndroidResolver;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.VM;
import com.vortexdbg.linux.android.dvm.jni.ProxyClassFactory;
import com.vortexdbg.linux.android.dvm.mcp.DvmMcpTools;
import com.vortexdbg.memory.Memory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * MCP harness for 04app (com.example.store): a C++ native target with a real linked structure.
 * It calls Store.build() at startup so the alice->bob->carol chain exists, then exposes the MCP
 * tools. Use call_symbol store_head_addr to get &g_head, then read_pointer / read_typed /
 * read_string / read_memory / search_memory walk the real Session structs.
 */
public class StoreHarness {

    public static void main(String[] args) throws Exception {
        File appJar = new File("tests/MCP/04app/out/store.jar");
        File apk = new File("tests/MCP/04app/out/store.apk");
        File soDir = Files.createTempDirectory("store").toFile();
        soDir.deleteOnExit();
        File soFile = new File(soDir, "libstore.so");
        soFile.deleteOnExit();
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libstore.so");
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, soFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        AndroidEmulator emulator = new AndroidARM64Emulator("store",
                new File("target/rootfs"),
                Collections.<BackendFactory>singletonList(new Unicorn2Factory(true))) {};
        Memory memory = emulator.getMemory();
        memory.setLibraryResolver(new AndroidResolver(23));

        final VM vm = emulator.createDalvikVM(apk);
        vm.setVerbose(false);
        vm.setDvmClassFactory(new ProxyClassFactory(new VortexClassLoader(appJar)));
        vm.loadLibrary(soFile, false);

        final DvmClass store = vm.resolveClass("com/example/store/Store");
        int n = store.callStaticJniMethodInt(emulator, "build()I"); // build the chain up front
        System.out.println("===== Vortex-DBG Store MCP harness (04app, real C++ linked structure) =====");
        System.out.println("module: libstore.so (arm64, C++). built " + n + " Session nodes (alice->bob->carol).");
        System.out.println("Session = {u32 id; u32 flags; char name[16]; Session* next} (32 bytes).");
        System.out.println("Type `mcp`, then: call_symbol store_head_addr -> read_pointer/read_typed/read_string.");
        System.out.println("Trigger: `run score`.\n");

        Debugger debugger = emulator.attach();
        debugger.addMcpToolProvider(new DvmMcpTools(emulator, vm));

        McpToolkit toolkit = new McpToolkit();
        toolkit.addTool(new McpTool() {
            @Override public String name() { return "score"; }
            @Override public String description() { return "Store.rootScore(): walks the chain + access() su checks"; }
            @Override public String[] paramNames() { return new String[]{}; }
            @Override public void execute(String[] p) {
                System.out.println("rootScore = " + store.callStaticJniMethodInt(emulator, "rootScore()I"));
            }
        });
        toolkit.run(debugger);

        emulator.close();
    }
}
