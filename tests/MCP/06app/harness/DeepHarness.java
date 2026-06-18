package com.vortexdbg.mcpdeep;

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
 * MCP harness for 06app (com.example.deep): a deep native call chain. Break inside deep_level3 and
 * use get_callstack (real multi-frame backtrace) and get_threads (the running task) while paused.
 * Trigger: `run compute <n>`.
 */
public class DeepHarness {

    public static void main(String[] args) throws Exception {
        File appJar = new File("tests/MCP/06app/out/deep.jar");
        File apk = new File("tests/MCP/06app/out/deep.apk");
        File soDir = Files.createTempDirectory("deep").toFile();
        soDir.deleteOnExit();
        File soFile = new File(soDir, "libdeep.so");
        soFile.deleteOnExit();
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libdeep.so");
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, soFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        AndroidEmulator emulator = new AndroidARM64Emulator("deep",
                new File("target/rootfs"),
                Collections.<BackendFactory>singletonList(new Unicorn2Factory(true))) {};
        Memory memory = emulator.getMemory();
        memory.setLibraryResolver(new AndroidResolver(23));

        final VM vm = emulator.createDalvikVM(apk);
        vm.setVerbose(false);
        vm.setDvmClassFactory(new ProxyClassFactory(new VortexClassLoader(appJar)));
        vm.loadLibrary(soFile, false);

        final DvmClass deep = vm.resolveClass("com/example/deep/Deep");

        System.out.println("===== Vortex-DBG Deep MCP harness (06app, deep native call chain) =====");
        System.out.println("module: libdeep.so (arm64). chain: compute -> deep_level1 -> deep_level2 -> deep_level3.");
        System.out.println("Type `mcp`, then: add_breakpoint_by_symbol deep_level3 -> run compute -> get_callstack/get_threads.");
        System.out.println("Trigger: `run compute <n>`.\n");

        Debugger debugger = emulator.attach();
        debugger.addMcpToolProvider(new DvmMcpTools(emulator, vm));

        McpToolkit toolkit = new McpToolkit();
        toolkit.addTool(new McpTool() {
            @Override public String name() { return "compute"; }
            @Override public String description() { return "Deep.compute(n): runs the 3-level native call chain"; }
            @Override public String[] paramNames() { return new String[]{"n"}; }
            @Override public void execute(String[] p) {
                int n = p.length > 0 ? Integer.parseInt(p[0]) : 7;
                System.out.println("compute(" + n + ") = " + deep.callStaticJniMethodInt(emulator, "compute(I)I", n));
            }
        });
        toolkit.run(debugger);

        emulator.close();
    }
}
