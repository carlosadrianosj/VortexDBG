package com.vortexdbg.android.ida;

import com.vortexdbg.AbstractEmulator;
import com.vortexdbg.Emulator;
import com.vortexdbg.LibraryResolver;
import com.vortexdbg.Module;
import com.vortexdbg.Symbol;
import com.vortexdbg.arm.HookStatus;
import com.vortexdbg.arm.context.RegisterContext;
import com.vortexdbg.debugger.ida.Utils;
import com.vortexdbg.file.FileResult;
import com.vortexdbg.file.IOResolver;
import com.vortexdbg.file.linux.AndroidFileIO;
import com.vortexdbg.hook.ReplaceCallback;
import com.vortexdbg.hook.hookzz.HookEntryInfo;
import com.vortexdbg.hook.hookzz.HookZz;
import com.vortexdbg.hook.hookzz.HookZzArm64RegisterContext;
import com.vortexdbg.hook.hookzz.IHookZz;
import com.vortexdbg.hook.hookzz.WrapCallback;
import com.vortexdbg.hook.xhook.IxHook;
import com.vortexdbg.linux.android.AndroidResolver;
import com.vortexdbg.linux.android.XHookImpl;
import com.vortexdbg.linux.file.ByteArrayFileIO;
import com.vortexdbg.linux.file.DirectoryFileIO;
import com.vortexdbg.linux.file.MapsFileIO;
import com.vortexdbg.linux.file.SimpleFileIO;
import com.vortexdbg.memory.Memory;
import com.vortexdbg.pointer.VortexdbgPointer;
import com.vortexdbg.utils.Inspector;
import com.sun.jna.Pointer;
import org.apache.commons.codec.binary.Hex;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class AndroidServer64Test implements IOResolver<AndroidFileIO> {

    public static void main(String[] args) throws IOException {
        new AndroidServer64Test().test();
    }

    @Override
    public FileResult<AndroidFileIO> resolve(Emulator<AndroidFileIO> emulator, String pathname, int oflags) {
        final int attachPid = emulator.getPid() - 1;
        if (("/proc/" + attachPid + "/maps").equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new MapsFileIO(emulator, oflags, pathname, emulator.getMemory().getLoadedModules()));
        }
        if (("/proc/" + attachPid + "/cmdline").equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new ByteArrayFileIO(oflags, pathname, ("/system/bin/" + executable.getName()).getBytes()));
        }
        if (("/proc/" + attachPid + "/task/" + attachPid + "/comm").equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new ByteArrayFileIO(oflags, pathname, (executable.getName() + "\n").getBytes()));
        }
        if (("/proc/" + attachPid + "/exe").equals(pathname) || "/system/bin/android_server64_7.4".equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new SimpleFileIO(oflags, executable, pathname));
        }
        if ("/proc".equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new DirectoryFileIO(oflags, pathname, new DirectoryFileIO.DirectoryEntry(false, Integer.toString(attachPid))));
        }
        if (("/proc/" + attachPid).equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new DirectoryFileIO(oflags, pathname,
                    new DirectoryFileIO.DirectoryEntry(true, "maps"),
                    new DirectoryFileIO.DirectoryEntry(true, "cmdline"),
                    new DirectoryFileIO.DirectoryEntry(true, "exe"),
                    new DirectoryFileIO.DirectoryEntry(false, "task")));
        }

        return null;
    }

    private final Emulator<AndroidFileIO> emulator;
    private final Module module;
    private final File executable;

    private AndroidServer64Test() {
        executable = new File("vortexdbg-android/src/test/resources/example_binaries/ida/android_server64_7.4");
        emulator = new MyAndroidARM64Emulator(executable);
        emulator.getSyscallHandler().addIOResolver(this);
        Memory memory = emulator.getMemory();
        LibraryResolver resolver = new AndroidResolver(23);
        memory.setLibraryResolver(resolver);

        module = emulator.loadLibrary(executable);
    }

    private void test() {
        IxHook ixHook = XHookImpl.getInstance(emulator);
        ixHook.register(executable.getName(), "ptrace", new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator<?> emulator, long originFunction) {
                RegisterContext context = emulator.getContext();
                int request = context.getIntArg(0);
                Pointer addr = context.getPointerArg(2);
                Pointer data = context.getPointerArg(3);
                if (request != PTrace.PTRACE_PEEKTEXT && request != PTrace.PTRACE_POKEDATA) {
                    System.out.println("ptrace request=0x" + Integer.toHexString(request) + ", addr=" + addr + ", data=" + data + ", LR=" + context.getLRPointer());
                }
                return super.onCall(emulator, originFunction);
            }
        });
        /*ixHook.register(executable.getName(), "memcpy", new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator<?> emulator, long originFunction) {
                RegisterContext context = emulator.getContext();
                Pointer dest = context.getPointerArg(0);
                Pointer src = context.getPointerArg(1);
                int size = context.getIntArg(2);
                Inspector.inspect(src.getByteArray(0, size), "memcpy dest=" + dest + ", src=" + src + ", LR=" + context.getLRPointer());
                return super.onCall(emulator, originFunction);
            }
        });*/
        ixHook.refresh();

        IHookZz hookZz = HookZz.getInstance(emulator);
        Symbol pack_dd = module.findSymbolByName("pack_dd", false);
        hookZz.wrap(pack_dd, new WrapCallback<HookZzArm64RegisterContext>() {
            @Override
            public void preCall(Emulator<?> emulator, HookZzArm64RegisterContext ctx, HookEntryInfo info) {
                Pointer data = ctx.getPointerArg(0);
                int value = ctx.getIntArg(2);
                ctx.push(data);
                ctx.push(value & 0xffffffffL);
            }
            @Override
            public void postCall(Emulator<?> emulator, HookZzArm64RegisterContext ctx, HookEntryInfo info) {
                super.postCall(emulator, ctx, info);
                long value = ctx.pop();
                VortexdbgPointer data = ctx.pop();
                VortexdbgPointer end = ctx.getPointerArg(0);
                int size = (int) (end.toUIntPeer() - data.toUIntPeer());
                byte[] my = Utils.pack_dd(value);
                byte[] ida = data.getByteArray(0, size);
                long unpack = Utils.unpack_dd(ByteBuffer.wrap(ida));
                if (!Arrays.equals(my, ida) || unpack != value) {
                    Inspector.inspect(ida, "pack_dd value=0x" + Long.toHexString(value) + ", unpack=0x" + Long.toHexString(unpack) + ", my=" + Hex.encodeHexString(my));
                }
            }
        });
        Symbol pack_dq = module.findSymbolByName("pack_dq", false);
        hookZz.wrap(pack_dq, new WrapCallback<HookZzArm64RegisterContext>() {
            @Override
            public void preCall(Emulator<?> emulator, HookZzArm64RegisterContext ctx, HookEntryInfo info) {
                Pointer data = ctx.getPointerArg(0);
                long value = ctx.getLongArg(2);
                ctx.push(data);
                ctx.push(value);
            }
            @Override
            public void postCall(Emulator<?> emulator, HookZzArm64RegisterContext ctx, HookEntryInfo info) {
                super.postCall(emulator, ctx, info);
                long value = ctx.pop();
                VortexdbgPointer data = ctx.pop();
                VortexdbgPointer end = ctx.getPointerArg(0);
                int size = (int) (end.toUIntPeer() - data.toUIntPeer());
                byte[] my = Utils.pack_dq(value);
                byte[] ida = data.getByteArray(0, size);
                long unpack = Utils.unpack_dq(ByteBuffer.wrap(ida));
                if (!Arrays.equals(my, ida) || unpack != value) {
                    Inspector.inspect(ida, "pack_dq value=0x" + Long.toHexString(value) + ", unpack=0x" + Long.toHexString(unpack) + ", my=" + Hex.encodeHexString(my));
                }
            }
        });
        Symbol unpack_dd = module.findSymbolByName("unpack_dd", false);
        hookZz.wrap(unpack_dd, new WrapCallback<HookZzArm64RegisterContext>() {
            @Override
            public void preCall(Emulator<?> emulator, HookZzArm64RegisterContext ctx, HookEntryInfo info) {
                Pointer pointer = ctx.getPointerArg(0);
                Pointer data = pointer.getPointer(0);
                Pointer end = ctx.getPointerArg(1);
                ctx.push(data);
                ctx.push(end);
            }
            @Override
            public void postCall(Emulator<?> emulator, HookZzArm64RegisterContext ctx, HookEntryInfo info) {
                super.postCall(emulator, ctx, info);
                VortexdbgPointer end = ctx.pop();
                VortexdbgPointer data = ctx.pop();
                long value = ctx.getLongArg(0);
                int size = (int) (end.toUIntPeer() - data.toUIntPeer());
                byte[] bytes = data.getByteArray(0, size);
                long my = Utils.unpack_dd(ByteBuffer.wrap(bytes));
                if (value != my) {
                    Inspector.inspect(bytes, "unpack_dd data=" + data + ", value=0x" + Long.toHexString(value) + ", LR=" + ctx.getLRPointer());
                }
            }
        });
        Symbol unpack_dq = module.findSymbolByName("unpack_dq", false);
        hookZz.wrap(unpack_dq, new WrapCallback<HookZzArm64RegisterContext>() {
            @Override
            public void preCall(Emulator<?> emulator, HookZzArm64RegisterContext ctx, HookEntryInfo info) {
                Pointer pointer = ctx.getPointerArg(0);
                Pointer data = pointer.getPointer(0);
                Pointer end = ctx.getPointerArg(1);
                ctx.push(data);
                ctx.push(end);
            }
            @Override
            public void postCall(Emulator<?> emulator, HookZzArm64RegisterContext ctx, HookEntryInfo info) {
                super.postCall(emulator, ctx, info);
                VortexdbgPointer end = ctx.pop();
                VortexdbgPointer data = ctx.pop();
                long value = ctx.getLongArg(0);
                int size = (int) (end.toUIntPeer() - data.toUIntPeer());
                byte[] bytes = data.getByteArray(0, size);
                long my = Utils.unpack_dq(ByteBuffer.wrap(bytes));
                if (value != my) {
                    Inspector.inspect(bytes, "unpack_dq data=" + data + ", value=0x" + Long.toHexString(value) + ", LR=" + ctx.getLRPointer());
                }
            }
        });

        Logger.getLogger(AbstractEmulator.class).setLevel(Level.DEBUG);
//        emulator.attach().addBreakPoint(module, 0x02af44);
//        emulator.traceWrite(0x40314530L, 0x40314530L + 3);

        System.err.println("exit code: " + module.callEntry(emulator, "--verbose"));
    }

}
