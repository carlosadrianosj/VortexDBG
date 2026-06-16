package com.vortexdbg;

public class InvalidMemoryAccessException extends RuntimeException {

    public InvalidMemoryAccessException() {
    }

    public InvalidMemoryAccessException(String message) {
        super(message);
    }
}
