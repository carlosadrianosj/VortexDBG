package com.vortexdbg.arm;

import com.vortexdbg.LongJumpException;

public class NestedRun extends LongJumpException {

    public final long pc;

    private NestedRun(long pc) {
        this.pc = pc;
    }

    /**
     * need custom fix call context.
     */
    public static NestedRun runToFunction(long pc) {
        return new NestedRun(pc);
    }

}
