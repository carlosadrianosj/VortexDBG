package com.vortexdbg.ios.objc.processor;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.backend.BackendException;
import com.vortexdbg.ios.MachOModule;
import com.vortexdbg.ios.struct.objc.ObjcClass;
import com.vortexdbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import io.kaitai.MachO;

public class UniObjectiveProcessor extends CDObjectiveCProcessor {

    public UniObjectiveProcessor(Emulator<?> emulator, MachOModule module) {
        super(emulator, module);

        load();
    }

    final void loadClasses() {
        MachO.SegmentCommand64.Section64 section = objcSections.get("__objc_classlist");
        if (section == null) {
            return;
        }

        UnidbgPointer classListPointer = UnidbgPointer.pointer(emulator, module.base + section.addr());
        assert classListPointer != null;
        classListPointer.setSize(section.size());
        try {
            for (int i = 0; i < section.size(); i += 8) {
                Pointer item = classListPointer.getPointer(i);
                if (item == null) {
                    continue;
                }
                ObjcClass objcClass = ObjcClass.create(emulator, item);
                classList.add(objcClass);
            }
        } catch (BackendException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    final void loadCategories() {
    }

}
