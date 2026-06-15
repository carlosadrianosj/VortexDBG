package com.vortexdbg.spi;

import com.vortexdbg.LibraryResolver;
import com.vortexdbg.Module;
import com.vortexdbg.ModuleListener;
import com.vortexdbg.Symbol;
import com.vortexdbg.hook.HookListener;
import com.vortexdbg.pointer.UnidbgPointer;

import java.io.File;
import java.util.Collection;
import java.util.Map;

@SuppressWarnings("unused")
public interface Loader {

    void setLibraryResolver(LibraryResolver libraryResolver);

    void disableCallInitFunction();

    void setCallInitFunction(boolean callInit);

    Module load(File elfFile);
    Module load(File elfFile, boolean forceCallInit);

    Module load(LibraryFile libraryFile);
    Module load(LibraryFile libraryFile, boolean forceCallInit);

    Module findModuleByAddress(long address);
    Module findModule(String name);

    Module dlopen(String filename);
    Module dlopen(String filename, boolean calInit);
    boolean dlclose(long handle);
    Symbol dlsym(long handle, String symbol);

    void addModuleListener(ModuleListener listener);

    void addHookListener(HookListener listener);

    Collection<Module> getLoadedModules();

    String getMaxLengthLibraryName();
    long getMaxSizeOfLibrary();

    /**
     * 加载虚拟模块
     */
    Module loadVirtualModule(String name, final Map<String, UnidbgPointer> symbols);

}
