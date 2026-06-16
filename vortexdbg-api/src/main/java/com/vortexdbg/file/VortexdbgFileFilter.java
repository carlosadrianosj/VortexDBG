package com.vortexdbg.file;

import java.io.File;
import java.io.FileFilter;

public class VortexdbgFileFilter implements FileFilter {

    public static final String UNIDBG_PREFIX = "__ignore.vortexdbg";

    @Override
    public boolean accept(File pathname) {
        return !pathname.getName().startsWith(UNIDBG_PREFIX);
    }

}
