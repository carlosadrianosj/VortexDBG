package com.vortexdbg.spi;

import com.vortexdbg.Emulator;
import com.vortexdbg.Symbol;
import com.vortexdbg.hook.HookListener;
import com.vortexdbg.memory.Memory;
import com.vortexdbg.memory.SvcMemory;
import com.vortexdbg.pointer.UnidbgPointer;
import com.vortexdbg.serialize.Serializable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutput;

public abstract class Dlfcn implements HookListener, Serializable {

    private static final Logger log = LoggerFactory.getLogger(Dlfcn.class);

    protected final UnidbgPointer error;

    protected Dlfcn(SvcMemory svcMemory) {
        error = svcMemory.allocate(0x80, "Dlfcn.error");
        assert error != null;
        error.setMemory(0, 0x80, (byte) 0);
    }

    protected final long dlsym(Emulator<?> emulator, long handle, String symbolName) {
        Memory memory = emulator.getMemory();
        Symbol symbol = memory.dlsym(handle, symbolName);
        if (symbol == null) {
            log.info("Find symbol \"{}\" failed: handle=0x{}, LR={}", symbolName, Long.toHexString(handle), emulator.getContext().getLRPointer());
            this.error.setString(0, "Find symbol " + symbolName + " failed");
            return 0;
        }
        return symbol.getAddress();
    }

    @Override
    public void serialize(DataOutput out) {
        throw new UnsupportedOperationException();
    }
}
