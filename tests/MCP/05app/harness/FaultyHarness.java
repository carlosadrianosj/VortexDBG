package com.vortexdbg.mcpfaulty;

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
import com.vortexdbg.linux.android.dvm.StringObject;
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
 * MCP harness for 05app (com.example.faulty): the native risky() throws a Java exception via JNI,
 * so dvm_pending_exception reports a real pending exception. Use dvm_call_static Faulty.risky("...")
 * then dvm_pending_exception.
 */
public class FaultyHarness {

    public static void main(String[] args) throws Exception {
        File appJar = new File("tests/MCP/05app/out/faulty.jar");
        File apk = new File("tests/MCP/05app/out/faulty.apk");
        File soDir = Files.createTempDirectory("faulty").toFile();
        soDir.deleteOnExit();
        File soFile = new File(soDir, "libfaulty.so");
        soFile.deleteOnExit();
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libfaulty.so");
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, soFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        AndroidEmulator emulator = new AndroidARM64Emulator("faulty",
                new File("target/rootfs"),
                Collections.<BackendFactory>singletonList(new Unicorn2Factory(true))) {};
        Memory memory = emulator.getMemory();
        memory.setLibraryResolver(new AndroidResolver(23));

        final VM vm = emulator.createDalvikVM(apk);
        vm.setVerbose(false);
        vm.setDvmClassFactory(new ProxyClassFactory(new VortexClassLoader(appJar)));
        vm.loadLibrary(soFile, false);

        final DvmClass faulty = vm.resolveClass("com/example/faulty/Faulty");

        System.out.println("===== Vortex-DBG Faulty MCP harness (05app, JNI exceptions) =====");
        System.out.println("module: libfaulty.so (arm64). risky(\"ok\")=accepted; else native ThrowNew.");
        System.out.println("Type `mcp`, then: dvm_call_static Faulty.risky(\"x\") -> dvm_pending_exception.");
        System.out.println("Trigger: `run risky <input>`.\n");

        Debugger debugger = emulator.attach();
        debugger.addMcpToolProvider(new DvmMcpTools(emulator, vm));

        McpToolkit toolkit = new McpToolkit();
        toolkit.addTool(new McpTool() {
            @Override public String name() { return "risky"; }
            @Override public String description() { return "Faulty.risky(input): native throws IllegalArgumentException unless input is 'ok'"; }
            @Override public String[] paramNames() { return new String[]{"input"}; }
            @Override public void execute(String[] p) {
                String input = p.length > 0 ? p[0] : "ok";
                Object r = faulty.callStaticJniMethodObject(emulator,
                        "risky(Ljava/lang/String;)Ljava/lang/String;", new StringObject(vm, input));
                System.out.println("risky(" + input + ") = " + (r == null ? "null"
                        : ((com.vortexdbg.linux.android.dvm.DvmObject<?>) r).getValue()));
            }
        });
        toolkit.run(debugger);

        emulator.close();
    }
}
