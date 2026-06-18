package com.vortexdbg.mcpsecure;

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
 * MCP harness for 03app (com.example.secure): a real C++ native target. Its global libc++
 * std::string (g_last_plaintext) lets read_std_string be exercised on an ACTUAL std::string.
 * Use: dvm_call_static Secure.process("..."), then call_symbol libsecure.so secure_plaintext_addr
 * to get the std::string address, then read_std_string at it.
 */
public class SecureHarness {

    public static void main(String[] args) throws Exception {
        File appJar = new File("tests/MCP/03app/out/secure.jar");
        File apk = new File("tests/MCP/03app/out/secure.apk");
        File soDir = Files.createTempDirectory("secure").toFile();
        soDir.deleteOnExit();
        File soFile = new File(soDir, "libsecure.so");
        soFile.deleteOnExit();
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libsecure.so");
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, soFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        AndroidEmulator emulator = new AndroidARM64Emulator("secure",
                new File("target/rootfs"),
                Collections.<BackendFactory>singletonList(new Unicorn2Factory(true))) {};
        Memory memory = emulator.getMemory();
        memory.setLibraryResolver(new AndroidResolver(23));

        final VM vm = emulator.createDalvikVM(apk);
        vm.setVerbose(false);
        vm.setDvmClassFactory(new ProxyClassFactory(new VortexClassLoader(appJar)));
        vm.loadLibrary(soFile, false); // runs init_array -> constructs the C++ std::string global

        final DvmClass secure = vm.resolveClass("com/example/secure/Secure");

        System.out.println("===== Vortex-DBG Secure MCP harness (03app, real C++ std::string/std::vector) =====");
        System.out.println("module: libsecure.so (arm64, C++). exports: Secure.process, secure_plaintext_addr.");
        System.out.println("Type `mcp`, then: process -> call_symbol secure_plaintext_addr -> read_std_string.");
        System.out.println("Trigger: `run process <text>`.\n");

        Debugger debugger = emulator.attach();
        debugger.addMcpToolProvider(new DvmMcpTools(emulator, vm));

        McpToolkit toolkit = new McpToolkit();
        toolkit.addTool(new McpTool() {
            @Override public String name() { return "process"; }
            @Override public String description() { return "Secure.process(text): C++ rolling-key cipher; stashes plaintext in a std::string"; }
            @Override public String[] paramNames() { return new String[]{"text"}; }
            @Override public void execute(String[] p) {
                String text = p.length > 0 ? p[0] : "license-key-1234";
                DvmObject<?> r = secure.callStaticJniMethodObject(emulator,
                        "process(Ljava/lang/String;)Ljava/lang/String;", new StringObject(vm, text));
                System.out.println("process(" + text + ") = " + r.getValue());
            }
        });
        toolkit.run(debugger);

        emulator.close();
    }
}
