package com.vortexdbg.keychain;

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
 * MCP playground for the BIDIRECTIONAL keychain (libkeychain.so): KeyChain.generate is native and
 * calls BACK into Java (salt()/hex()). Unlike the TikTok AES lib, this exercises the native->Java
 * JNI bridge, so the hook tools (dvm_trace_jni / dvm_mock_jni / dvm_break_on_jni) have something to
 * observe. Run it, type `mcp`, connect, then:
 *   dvm_trace_jni {enable:true}
 *   dvm_call_static {class:"com/example/keychain/KeyChain", method:"generate(Ljava/lang/String;)Ljava/lang/String;", args:["alice"]}
 *   dvm_jni_log {}            // shows salt() / hex() callbacks
 *   dvm_mock_jni {signature:"hex", return:"deadbeef"} ; then call generate again
 */
public class KeyChainMcp {

    public static void main(String[] args) throws Exception {
        File appJar = new File("tests/keychain-test/out/keychain.jar");
        File apk = new File("tests/keychain-test/out/keychain.apk");
        File soFile = File.createTempFile("libkeychain", ".so");
        soFile.deleteOnExit();
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libkeychain.so");
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, soFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        AndroidEmulator emulator = new AndroidARM64Emulator("keychain",
                new File("target/rootfs"),
                Collections.<BackendFactory>singletonList(new Unicorn2Factory(true))) {};
        Memory memory = emulator.getMemory();
        memory.setLibraryResolver(new AndroidResolver(23));

        final VM vm = emulator.createDalvikVM();
        vm.setVerbose(false);
        vm.setDvmClassFactory(new ProxyClassFactory(new VortexClassLoader(appJar)));
        vm.loadLibrary(soFile, false);
        final DvmClass kc = vm.resolveClass("com/example/keychain/KeyChain");

        System.out.println("===== Vortex-DBG KeyChain MCP playground (native<->Java callbacks) =====");
        System.out.println("Type `mcp`, connect, then dvm_trace_jni{enable:true} + call generate + dvm_jni_log.");

        Debugger debugger = emulator.attach();
        debugger.addMcpToolProvider(new DvmMcpTools(emulator, vm));

        McpToolkit toolkit = new McpToolkit();
        toolkit.addTool(new McpTool() {
            @Override public String name() { return "gen"; }
            @Override public String description() { return "KeyChain.generate(account) - native calls back into Java salt()/hex()"; }
            @Override public String[] paramNames() { return new String[]{"account"}; }
            @Override public void execute(String[] params) {
                String account = params.length > 0 ? params[0] : "alice";
                Object ret = kc.callStaticJniMethodObject(emulator,
                        "generate(Ljava/lang/String;)Ljava/lang/String;", new StringObject(vm, account));
                System.out.println("generate(" + account + ") = " + ((com.vortexdbg.linux.android.dvm.DvmObject<?>) ret).getValue());
            }
        });
        toolkit.run(debugger);

        emulator.close();
    }
}
