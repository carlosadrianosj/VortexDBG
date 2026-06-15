package com.github.unidbg.debugger;

import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.BlockHook;
import com.github.unidbg.arm.backend.DebugHook;

import java.util.Map;

public interface Debugger extends Breaker, DebugHook, BlockHook {

    BreakPoint addBreakPoint(Module module, String symbol);
    BreakPoint addBreakPoint(Module module, String symbol, BreakPointCallback callback);
    BreakPoint addBreakPoint(Module module, long offset);
    BreakPoint addBreakPoint(Module module, long offset, BreakPointCallback callback);

    /**
     * @param address 奇数地址表示thumb断点
     */
    BreakPoint addBreakPoint(long address);
    BreakPoint addBreakPoint(long address, BreakPointCallback callback);

    void traceFunctionCall(FunctionCallListener listener);

    /**
     * use with unicorn
     * @param module <code>null</code> means all modules.
     */
    void traceFunctionCall(Module module, FunctionCallListener listener);

    @SuppressWarnings("unused")
    void setDebugListener(DebugListener listener);

    <T> T run(DebugRunnable<T> runnable) throws Exception;

    boolean hasRunnable();

    boolean isDebugging();

    void disassembleBlock(Emulator<?> emulator, long address, boolean thumb);

    void addMcpTool(String name, String description, String... paramNames);

    boolean removeBreakPoint(long address);

    Map<Long, BreakPoint> getBreakPoints();

    void close();

}
