package com.vortexdbg;

import com.vortexdbg.arm.backend.Backend;
import com.vortexdbg.arm.context.RegisterContext;
import com.vortexdbg.debugger.Debugger;
import com.vortexdbg.debugger.DebuggerType;
import com.vortexdbg.file.FileSystem;
import com.vortexdbg.file.NewFileIO;
import com.vortexdbg.listener.TraceCodeListener;
import com.vortexdbg.listener.TraceReadListener;
import com.vortexdbg.listener.TraceSystemMemoryWriteListener;
import com.vortexdbg.listener.TraceWriteListener;
import com.vortexdbg.memory.Memory;
import com.vortexdbg.memory.MemoryTracker;
import com.vortexdbg.memory.SvcMemory;
import com.vortexdbg.serialize.Serializable;
import com.vortexdbg.spi.ArmDisassembler;
import com.vortexdbg.spi.Dlfcn;
import com.vortexdbg.spi.LibraryFile;
import com.vortexdbg.spi.SyscallHandler;
import com.vortexdbg.thread.ThreadDispatcher;
import com.vortexdbg.unwind.Unwinder;

import java.io.Closeable;
import java.io.File;
import java.net.URL;

/**
 * cpu emulator
 * Created by zhkl0228 on 2017/5/2.
 */

public interface Emulator<T extends NewFileIO> extends Closeable, ArmDisassembler, Serializable {

    int getPointerSize();

    boolean is64Bit();
    boolean is32Bit();

    int getPageAlign();

    /**
     * trace memory read
     */
    TraceHook traceRead();
    TraceHook traceRead(long begin, long end);
    TraceHook traceRead(long begin, long end, TraceReadListener listener);

    /**
     * trace memory write
     */
    TraceHook traceWrite();
    TraceHook traceWrite(long begin, long end);
    TraceHook traceWrite(long begin, long end, TraceWriteListener listener);

    void setTraceSystemMemoryWrite(long begin, long end, TraceSystemMemoryWriteListener listener);

    /**
     * trace instruction
     * note: low performance
     */
    TraceHook traceCode();
    TraceHook traceCode(long begin, long end);
    TraceHook traceCode(long begin, long end, TraceCodeListener listener);

    Number eFunc(long begin, Number... arguments);

    Number eEntry(long begin, long sp);

    /**
     * emulate signal handler
     * @param sig signal number
     * @return <code>true</code> means called handler function.
     */
    boolean emulateSignal(int sig);

    /**
     * 是否正在运行
     */
    boolean isRunning();

    /**
     * show all registers
     */
    void showRegs();

    /**
     * show registers
     */
    void showRegs(int... regs);

    Module loadLibrary(File libraryFile);
    Module loadLibrary(File libraryFile, boolean forceCallInit);

    Memory getMemory();

    Backend getBackend();

    int getPid();

    String getProcessName();

    Debugger attach();

    Debugger attach(DebuggerType type);

    FileSystem<T> getFileSystem();

    SvcMemory getSvcMemory();

    SyscallHandler<T> getSyscallHandler();

    Family getFamily();
    LibraryFile createURLibraryFile(URL url, String libName);

    Dlfcn getDlfcn();

    /**
     * @param timeout  Duration to emulate the code (in microseconds). When this value is 0, we will emulate the code in infinite time, until the code is finished.
     */
    void setTimeout(long timeout);

    <V extends RegisterContext> V getContext();

    Unwinder getUnwinder();

    /**
     * Start tracking memory allocations (mmap/munmap/brk) for leak detection.
     * Use with try-with-resources: close() restores previous listener and prints the leak report.
     */
    default MemoryTracker traceMemoryLeaks() {
        return new MemoryTracker(this);
    }

    void pushContext(int off);
    int popContext();

    ThreadDispatcher getThreadDispatcher();

    long getReturnAddress();

    void set(String key, Object value);
    <V> V get(String key);

    /**
     * Get Objective-C class name for the object at the given address.
     * Reads isa pointer, applies ISA_MASK, and parses class_ro_t to get the name.
     * Only available on iOS emulators.
     */
    default String getObjcClassName(long address) {
        throw new UnsupportedOperationException("ObjC introspection only available on iOS emulators");
    }

    /**
     * Dump Objective-C class definition (properties, methods, protocols, ivars).
     * Only available on iOS emulators with ObjC runtime loaded.
     */
    default String dumpObjcClass(String className) {
        throw new UnsupportedOperationException("ObjC class dump only available on iOS emulators");
    }

    /**
     * Dump GPB (Google Protobuf for Objective-C) message definition as .proto format.
     * Only available on iOS 64-bit emulators with GPB protobuf library loaded.
     */
    default String dumpGPBProtobufDef(String className) {
        throw new UnsupportedOperationException("GPB protobuf dump only available on iOS 64-bit emulators");
    }

}
