package com.example.deep;

/**
 * A native target with a deliberately deep, non-inlined call chain
 * (compute -> deep_level1 -> deep_level2 -> deep_level3), so a breakpoint inside the innermost
 * function yields a real multi-frame backtrace for get_callstack, and get_threads shows the running
 * task while paused mid-call.
 */
public class Deep {

    /** Returns a value computed through a 3-level native call chain. */
    public static native int compute(int n);
}
