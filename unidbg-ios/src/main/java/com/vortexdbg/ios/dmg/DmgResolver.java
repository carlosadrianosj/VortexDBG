package com.vortexdbg.ios.dmg;

import com.vortexdbg.Emulator;
import com.vortexdbg.file.FileResult;
import com.vortexdbg.file.IOResolver;
import com.vortexdbg.file.ios.DarwinFileIO;
import com.vortexdbg.ios.file.DirectoryFileIO;
import com.vortexdbg.ios.file.SimpleFileIO;
import org.apache.commons.io.FilenameUtils;

import java.io.File;

class DmgResolver implements IOResolver<DarwinFileIO> {

    private final File dmgDir;

    DmgResolver(File dmgDir) {
        this.dmgDir = dmgDir;
    }

    @Override
    public FileResult<DarwinFileIO> resolve(Emulator<DarwinFileIO> emulator, String pathname, int oflags) {
        pathname = FilenameUtils.normalize(pathname, true);
        String dmgDir = FilenameUtils.normalize(this.dmgDir.getAbsolutePath(), true);
        if (pathname.startsWith(dmgDir)) {
            File file = new File(pathname);
            if (file.exists()) {
                return file.isFile() ? FileResult.<DarwinFileIO>success(new SimpleFileIO(oflags, file, pathname)) : FileResult.<DarwinFileIO>success(new DirectoryFileIO(oflags, pathname, file));
            }
        }

        return null;
    }

}
