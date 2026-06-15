package com.vortexdbg.ios.classdump;

import com.vortexdbg.Emulator;
import com.vortexdbg.hook.BaseHook;
import com.vortexdbg.ios.objc.ObjC;
import com.vortexdbg.ios.struct.objc.ObjcClass;
import com.vortexdbg.ios.struct.objc.ObjcObject;

public class ClassDumper extends BaseHook implements IClassDumper {

    public static ClassDumper getInstance(Emulator<?> emulator) {
        ClassDumper classDumper = emulator.get(ClassDumper.class.getName());
        if (classDumper == null) {
            classDumper = new ClassDumper(emulator);
            emulator.set(ClassDumper.class.getName(), classDumper);
        }
        return classDumper;
    }

    private ClassDumper(Emulator<?> emulator) {
        super(emulator, "libclassdump");
    }

    @Override
    public String dumpClass(String className) {
        ObjC objc = ObjC.getInstance(emulator);
        ObjcClass oClassDump = objc.getClass("ClassDump");
        ObjcObject str = oClassDump.callObjc("my_dump_class:", className);
        return str == null ? null : str.getDescription();
    }

    @Override
    public void searchClass(String keywords) {
        ObjC objc = ObjC.getInstance(emulator);
        ObjcClass oClassDump = objc.getClass("ClassDump");
        oClassDump.callObjc("search_class:", keywords);
    }
}
