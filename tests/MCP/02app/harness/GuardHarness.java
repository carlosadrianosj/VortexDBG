package com.vortexdbg.mcpguard;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.app.VortexClassLoader;
import com.vortexdbg.arm.backend.BackendFactory;
import com.vortexdbg.arm.backend.Unicorn2Factory;
import com.vortexdbg.debugger.Debugger;
import com.vortexdbg.debugger.McpTool;
import com.vortexdbg.debugger.McpToolkit;
import com.vortexdbg.linux.android.AndroidARM64Emulator;
import com.vortexdbg.linux.android.AndroidResolver;
import com.vortexdbg.linux.android.dvm.DalvikModule;
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
 * MCP harness for 02app (com.example.guard). Its native methods are RegisterNatives-bound, so we
 * call JNI_OnLoad to populate the bindings, then expose the MCP tools. This app makes several tools
 * show a VISIBLE effect:
 *   - dvm_list_native_registrations / dvm_resolve_method -> the RegisterNatives bindings.
 *   - dvm_spoof_env -> deviceModel()/isEmulator()/bootToken() actually change (they read Build /
 *     System through JNI).
 *   - dvm_trace_jni / dvm_mock_jni -> observe / override the Build/System callbacks.
 */
public class GuardHarness {

    public static void main(String[] args) throws Exception {
        File appJar = new File("tests/MCP/02app/out/guard.jar");
        File apk = new File("tests/MCP/02app/out/guard.apk");
        File soDir = Files.createTempDirectory("guard").toFile();
        soDir.deleteOnExit();
        File soFile = new File(soDir, "libguard.so");
        soFile.deleteOnExit();
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libguard.so");
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, soFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        AndroidEmulator emulator = new AndroidARM64Emulator("guard",
                new File("target/rootfs"),
                Collections.<BackendFactory>singletonList(new Unicorn2Factory(true))) {};
        Memory memory = emulator.getMemory();
        memory.setLibraryResolver(new AndroidResolver(23));

        final VM vm = emulator.createDalvikVM(apk);
        vm.setVerbose(false);
        vm.setDvmClassFactory(new ProxyClassFactory(new VortexClassLoader(appJar)));
        DalvikModule dm = vm.loadLibrary(soFile, false);
        dm.callJNI_OnLoad(emulator); // <-- triggers RegisterNatives so nativesMap is populated

        final DvmClass guard = vm.resolveClass("com/example/guard/Guard");

        System.out.println("===== Vortex-DBG Guard MCP harness (02app, RegisterNatives + Build reads) =====");
        System.out.println("module: libguard.so (arm64)  natives bound via RegisterNatives (JNI_OnLoad).");
        System.out.println("Try: dvm_list_native_registrations, then dvm_call_static Guard.isEmulator,");
        System.out.println("then dvm_spoof_env{preset:pixel} and call again -> the result changes.");
        System.out.println("Type `mcp` to start the server. Triggers: `run model`, `run emu`, `run token`.\n");

        Debugger debugger = emulator.attach();
        debugger.addMcpToolProvider(new DvmMcpTools(emulator, vm));

        McpToolkit toolkit = new McpToolkit();
        toolkit.addTool(simple("model", "Guard.deviceModel() -> android.os.Build.MODEL", guard, emulator,
                "deviceModel()Ljava/lang/String;"));
        toolkit.addTool(simple("emu", "Guard.isEmulator() -> reads Build.FINGERPRINT", guard, emulator,
                "isEmulator()Z"));
        toolkit.addTool(simple("token", "Guard.bootToken() -> System.currentTimeMillis()/1000", guard, emulator,
                "bootToken()J"));
        toolkit.run(debugger);

        emulator.close();
    }

    private static McpTool simple(final String name, final String desc, final DvmClass cls,
                                  final AndroidEmulator emulator, final String sig) {
        return new McpTool() {
            @Override public String name() { return name; }
            @Override public String description() { return desc; }
            @Override public String[] paramNames() { return new String[]{}; }
            @Override public void execute(String[] p) {
                Object r;
                if (sig.endsWith("Z")) r = cls.callStaticJniMethodBoolean(emulator, sig);
                else if (sig.endsWith("J")) r = cls.callStaticJniMethodLong(emulator, sig);
                else r = cls.callStaticJniMethodObject(emulator, sig).getValue();
                System.out.println(name + " = " + r);
            }
        };
    }
}
