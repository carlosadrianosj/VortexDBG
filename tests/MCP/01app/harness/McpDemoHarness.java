package com.vortexdbg.mcpdemo;

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
import com.vortexdbg.linux.android.dvm.DvmObject;
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
 * MCP test harness for the McpDemo app (com.example.mcpdemo).
 *
 * It wires up Vortex-DBG so that EVERY MCP tool (native/ARM side + Dalvik/Java side) has something
 * real to act on:
 *   - libvault.so (arm64) is loaded   -> native tools (list_modules/list_exports/disassemble/
 *     find_symbol/breakpoints/read_memory/call_symbol/traces/stepping).
 *   - the VM is created WITH the APK   -> dvm_dex_surface can read the embedded classes.dex,
 *     and resources/assets resolve.
 *   - Vault.seal (static, calls back Device.salt()/hex()) and Vault.transform (instance) are
 *     reachable -> dvm_call_static/dvm_call_instance + the JNI hooks (trace/mock/break).
 *   - two custom tools ("seal", "transform") let you trigger native execution for breakpoint demos.
 *
 * Run it, type `mcp` in the console, connect an MCP client to http://localhost:9239/sse.
 * See tests/MCP/README.md for the full tool-by-tool walkthrough.
 */
public class McpDemoHarness {

    public static void main(String[] args) throws Exception {
        File appJar = new File("tests/MCP/01app/out/mcpdemo.jar");
        File apk = new File("tests/MCP/01app/out/mcpdemo.apk");
        // Extract to a fixed filename so the loaded module is named "libvault.so" (the native
        // by-name MCP tools resolve modules by file name, not by a temp name).
        File soDir = Files.createTempDirectory("mcpdemo").toFile();
        soDir.deleteOnExit();
        File soFile = new File(soDir, "libvault.so");
        soFile.deleteOnExit();
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libvault.so");
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, soFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        AndroidEmulator emulator = new AndroidARM64Emulator("mcpdemo",
                new File("target/rootfs"),
                Collections.<BackendFactory>singletonList(new Unicorn2Factory(true))) {};
        Memory memory = emulator.getMemory();
        memory.setLibraryResolver(new AndroidResolver(23));

        // createDalvikVM(apk): give the VM the APK so dvm_dex_surface reads the embedded classes.dex.
        final VM vm = emulator.createDalvikVM(apk);
        vm.setVerbose(false);
        vm.setDvmClassFactory(new ProxyClassFactory(new VortexClassLoader(appJar)));
        vm.loadLibrary(soFile, false);

        final DvmClass vault = vm.resolveClass("com/example/mcpdemo/Vault");
        vm.resolveClass("com/example/mcpdemo/Device");

        System.out.println("===== Vortex-DBG McpDemo MCP harness (backend=" + emulator.getBackend() + ") =====");
        System.out.println("module: libvault.so (arm64)  exports: Vault.seal (static, callbacks), Vault.transform (instance)");
        System.out.println("VM created WITH apk -> dvm_dex_surface reads the embedded classes.dex.");
        System.out.println("Type `mcp` to start the server, then connect to http://localhost:9239/sse.");
        System.out.println("Triggers: `run seal <account> <secret>`, `run transform <text>`.\n");

        Debugger debugger = emulator.attach();
        debugger.addMcpToolProvider(new DvmMcpTools(emulator, vm));

        McpToolkit toolkit = new McpToolkit();
        toolkit.addTool(new McpTool() {
            @Override public String name() { return "seal"; }
            @Override public String description() { return "Vault.seal(account,secret): static native, calls back Device.salt()/hex()"; }
            @Override public String[] paramNames() { return new String[]{"account", "secret"}; }
            @Override public void execute(String[] p) {
                String account = p.length > 0 ? p[0] : "alice";
                String secret = p.length > 1 ? p[1] : "hunter2";
                DvmObject<?> r = vault.callStaticJniMethodObject(emulator,
                        "seal(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                        new StringObject(vm, account), new StringObject(vm, secret));
                System.out.println("seal(" + account + "," + secret + ") = " + r.getValue());
            }
        });
        toolkit.addTool(new McpTool() {
            @Override public String name() { return "transform"; }
            @Override public String description() { return "Vault(label).transform(text): instance native (reverse+upper)"; }
            @Override public String[] paramNames() { return new String[]{"text"}; }
            @Override public void execute(String[] p) {
                String text = p.length > 0 ? p[0] : "hello";
                DvmObject<?> v = vault.allocObject();
                DvmObject<?> r = v.callJniMethodObject(emulator,
                        "transform(Ljava/lang/String;)Ljava/lang/String;", new StringObject(vm, text));
                System.out.println("transform(" + text + ") = " + r.getValue());
            }
        });
        toolkit.run(debugger);

        emulator.close();
    }
}
