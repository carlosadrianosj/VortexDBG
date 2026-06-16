package com.vortexdbg.file.linux;

import com.vortexdbg.Emulator;
import com.vortexdbg.file.BaseFileSystem;
import com.vortexdbg.file.FileResult;
import com.vortexdbg.file.FileSystem;
import com.vortexdbg.linux.android.LogCatHandler;
import com.vortexdbg.linux.file.DirectoryFileIO;
import com.vortexdbg.linux.file.MapsFileIO;
import com.vortexdbg.linux.file.NullFileIO;
import com.vortexdbg.linux.file.SimpleFileIO;
import com.vortexdbg.linux.file.Stdin;
import com.vortexdbg.linux.file.Stdout;
import com.vortexdbg.unix.IO;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

public class LinuxFileSystem extends BaseFileSystem<AndroidFileIO> implements FileSystem<AndroidFileIO>, IOConstants {

    public LinuxFileSystem(Emulator<AndroidFileIO> emulator, File rootDir) {
        super(emulator, rootDir);
    }

    @Override
    public FileResult<AndroidFileIO> open(String pathname, int oflags) {
        if ("/dev/tty".equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new NullFileIO(pathname));
        }
        if ("/proc/self/maps".equals(pathname) || ("/proc/" + emulator.getPid() + "/maps").equals(pathname) ||
                ("/proc/self/task/" + emulator.getPid() + "/maps").equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new MapsFileIO(emulator, oflags, pathname, emulator.getMemory().getLoadedModules()));
        }

        return super.open(pathname, oflags);
    }

    public LogCatHandler getLogCatHandler() {
        return null;
    }

    @Override
    protected void initialize(File rootDir) throws IOException {
        super.initialize(rootDir);

        FileUtils.forceMkdir(new File(rootDir, "system"));
        FileUtils.forceMkdir(new File(rootDir, "data"));
    }

    @Override
    public AndroidFileIO createSimpleFileIO(File file, int oflags, String path) {
        return new SimpleFileIO(oflags, file, path);
    }

    @Override
    public AndroidFileIO createDirectoryFileIO(File file, int oflags, String path) {
        return new DirectoryFileIO(oflags, path, file);
    }

    @Override
    protected AndroidFileIO createStdin(int oflags) {
        return new Stdin(oflags);
    }

    @Override
    protected AndroidFileIO createStdout(int oflags, File stdio, String pathname) {
        return new Stdout(oflags, stdio, pathname, IO.STDERR.equals(pathname), null);
    }

    @Override
    protected boolean hasCreat(int oflags) {
        return (oflags & O_CREAT) != 0;
    }

    @Override
    protected boolean hasDirectory(int oflags) {
        return (oflags & O_DIRECTORY) != 0;
    }

    @Override
    protected boolean hasAppend(int oflags) {
        return (oflags & O_APPEND) != 0;
    }

    @Override
    protected boolean hasExcl(int oflags) {
        return (oflags & O_EXCL) != 0;
    }
}
