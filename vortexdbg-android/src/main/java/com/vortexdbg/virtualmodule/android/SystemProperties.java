package com.vortexdbg.virtualmodule.android;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.Arm64Hook;
import com.vortexdbg.arm.ArmHook;
import com.vortexdbg.arm.HookStatus;
import com.vortexdbg.arm.NestedRun;
import com.vortexdbg.arm.context.EditableArm32RegisterContext;
import com.vortexdbg.arm.context.EditableArm64RegisterContext;
import com.vortexdbg.linux.android.SystemPropertyHook;
import com.vortexdbg.memory.SvcMemory;
import com.vortexdbg.pointer.VortexdbgPointer;
import com.vortexdbg.virtualmodule.VirtualModule;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class SystemProperties extends VirtualModule<Void> {

    private static final Logger log = LoggerFactory.getLogger(SystemProperties.class);

    public SystemProperties(Emulator<?> emulator, Void extra) {
        super(emulator, extra, "libsystemproperties.so");
    }

    @Override
    protected void onInitialize(Emulator<?> emulator, Void extra, Map<String, VortexdbgPointer> symbols) {
        boolean is64Bit = emulator.is64Bit();
        SvcMemory svcMemory = emulator.getSvcMemory();
        symbols.put("__system_property_read_callback", svcMemory.registerSvc(is64Bit ? new Arm64Hook() {
            @Override
            public HookStatus hook(Emulator<?> emulator) {
                EditableArm64RegisterContext context = emulator.getContext();
                Pointer pi = context.getPointerArg(0);
                Pointer callback = context.getPointerArg(1);
                Pointer cookie = context.getPointerArg(2);
                log.debug("__system_property_read_callback pi={}, callback={}, cookie={}", pi, callback, cookie);
                Pointer key = pi.share(SystemPropertyHook.PROP_VALUE_MAX + 4);
                Pointer value = pi.share(4);
                context.setXLong(0, VortexdbgPointer.nativeValueOf(cookie));
                context.setXLong(1, VortexdbgPointer.nativeValueOf(value));
                context.setXLong(2, VortexdbgPointer.nativeValueOf(key));
                context.setXLong(3, pi.getInt(0));
                return HookStatus.RET(emulator, VortexdbgPointer.nativeValueOf(callback));
            }
        } : new ArmHook() {
            @Override
            protected HookStatus hook(Emulator<?> emulator) throws NestedRun {
                EditableArm32RegisterContext context = emulator.getContext();
                Pointer pi = context.getPointerArg(0);
                Pointer callback = context.getPointerArg(1);
                Pointer cookie = context.getPointerArg(2);
                log.debug("__system_property_read_callback pi={}, callback={}, cookie={}", pi, callback, cookie);
                Pointer key = pi.share(SystemPropertyHook.PROP_VALUE_MAX + 4);
                Pointer value = pi.share(4);
                context.setR0((int) VortexdbgPointer.nativeValueOf(cookie));
                context.setR1((int) VortexdbgPointer.nativeValueOf(value));
                context.setR2((int) VortexdbgPointer.nativeValueOf(key));
                context.setR3(pi.getInt(0));
                return HookStatus.RET(emulator, VortexdbgPointer.nativeValueOf(callback));
            }
        }));
    }

}
