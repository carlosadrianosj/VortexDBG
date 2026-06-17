package com.vortexdbg.aeskeychain;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.app.VortexClassLoader;
import com.vortexdbg.arm.backend.Unicorn2Factory;
import com.vortexdbg.debugger.Debugger;
import com.vortexdbg.debugger.McpTool;
import com.vortexdbg.debugger.McpToolkit;
import com.vortexdbg.linux.android.AndroidEmulatorBuilder;
import com.vortexdbg.linux.android.AndroidResolver;
import com.vortexdbg.linux.android.dvm.DalvikModule;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.VM;
import com.vortexdbg.linux.android.dvm.array.ByteArray;
import com.vortexdbg.linux.android.dvm.jni.ProxyClassFactory;
import com.vortexdbg.linux.android.dvm.mcp.DvmMcpTools;
import com.vortexdbg.memory.Memory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Quick MCP playground for the SecureVault demo (mixed Java + native AES).
 *
 * Run this class, then in its console type `mcp` to start the MCP server, and connect an
 * AI client (Claude Code / Cursor) to http://localhost:9239/sse. You then have:
 *   - the built-in NATIVE tools (read_args, write_string, disassemble_symbol, disassemble,
 *     read_memory, call_symbol, breakpoints, traces, ...), driving libttEncrypt.so;
 *   - the DVM/Java tools (dvm_list_classes, dvm_list_objects, dvm_read_string,
 *     dvm_get_object, dvm_call_static), driving the host-JVM Dalvik side;
 *   - a custom `seal` tool that runs the full pipeline (Java framing -> native AES -> Java tag).
 *
 * Try from the AI client:
 *   dvm_list_classes
 *   dvm_call_static {class:"com/bytedance/frameworks/core/encrypt/TTEncryptUtils",
 *                    method:"ttEncrypt([BI)[B", args:["00000000000000000000000000000000","16"]}
 *   add_breakpoint_by_symbol {module_name:"libttEncrypt.so", symbol_name:"ss_encrypt"}
 *   seal {account:"alice", secret:"hunter2"}  ; then poll_events ; then read_args
 */
public class AesKeychainMcp {

    public static void main(String[] args) throws Exception {
        File appJar = new File("tests/keychain-aes-test/out/keychain-aes.jar");
        File apk = new File("tests/keychain-aes-test/out/keychain-aes.apk");
        File soFile = File.createTempFile("libttEncrypt", ".so");
        soFile.deleteOnExit();
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("lib/armeabi-v7a/libttEncrypt.so");
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, soFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        AndroidEmulator emulator = AndroidEmulatorBuilder.for32Bit()
                .setProcessName("com.example.aeskeychain")
                .addBackendFactory(new Unicorn2Factory(true))
                .build();
        Memory memory = emulator.getMemory();
        memory.setLibraryResolver(new AndroidResolver(23));

        final VM vm = emulator.createDalvikVM();
        vm.setVerbose(false);
        vm.setDvmClassFactory(new ProxyClassFactory(new VortexClassLoader(appJar)));
        DalvikModule dm = vm.loadLibrary(soFile, false);
        dm.callJNI_OnLoad(emulator);

        final DvmClass tt = vm.resolveClass("com/bytedance/frameworks/core/encrypt/TTEncryptUtils");
        vm.resolveClass("com/example/aeskeychain/SecureVault"); // so dvm_list_classes shows the app's Java side

        System.out.println("===== Vortex-DBG SecureVault MCP playground (backend=" + emulator.getBackend() + ") =====");
        System.out.println("native lib: libttEncrypt.so (32-bit ARM, real AES) from keychain-aes.apk");
        System.out.println("Type `mcp` to start the MCP server, then connect Claude Code / Cursor to http://localhost:9239/sse");
        System.out.println("Type `run seal <account> <secret>` to seal locally, or `c`/`exit` to quit.\n");

        Debugger debugger = emulator.attach();
        // Java/DEX-side MCP tools (host JVM Dalvik bridge):
        debugger.addMcpToolProvider(new DvmMcpTools(emulator, vm));

        McpToolkit toolkit = new McpToolkit();
        toolkit.addTool(new McpTool() {
            @Override public String name() { return "seal"; }
            @Override public String description() { return "Seal a secret for an account: Java framing -> native AES -> Java tag+hex"; }
            @Override public String[] paramNames() { return new String[]{"account", "secret"}; }
            @Override public void execute(String[] params) {
                String account = params.length > 0 ? params[0] : "alice";
                String secret = params.length > 1 ? params[1] : "hunter2";
                byte[] pt = AesKeychainAuto.block(account, secret);                 // JAVA (host)
                ByteArray ct = tt.callStaticJniMethodObject(emulator, "ttEncrypt([BI)[B",
                        new ByteArray(vm, pt), pt.length);                          // NATIVE (emulated)
                String token = AesKeychainAuto.combine(ct.getValue());             // JAVA (host)
                System.out.println("seal(" + account + ", " + secret + ") = " + token);
            }
        });
        toolkit.run(debugger);

        emulator.close();
    }
}
