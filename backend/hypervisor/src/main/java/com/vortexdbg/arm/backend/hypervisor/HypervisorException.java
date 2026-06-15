package com.vortexdbg.arm.backend.hypervisor;

public class HypervisorException extends RuntimeException {

    public HypervisorException() {
        super();
    }

    public HypervisorException(String msg) {
        super(msg);
    }

}
