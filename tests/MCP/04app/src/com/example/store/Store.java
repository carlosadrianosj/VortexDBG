package com.example.store;

/**
 * A native target with a REAL in-memory linked data structure, for exercising the pointer/struct
 * MCP tools (read_pointer chains, read_typed struct fields, read_memory, read_string, search_memory)
 * against actual heap objects rather than a scratch buffer. Also a multi-signal rootScore() that
 * walks the chain and probes the filesystem via access() (an emulated syscall).
 *
 * Session layout (arm64): { uint32 id; uint32 flags; char name[16]; Session* next; } = 32 bytes.
 */
public class Store {

    /** Builds a chain of Session nodes (alice -> bob -> carol) on the native heap; returns the count. */
    public static native int build();

    /** Multi-signal "risk" score: walks the chain (id ^ flags) + access() checks for su binaries. */
    public static native int rootScore();
}
