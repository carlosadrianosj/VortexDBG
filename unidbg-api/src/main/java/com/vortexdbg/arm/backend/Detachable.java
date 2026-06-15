package com.vortexdbg.arm.backend;

public interface Detachable {

    void onAttach(UnHook unHook);

    void detach();

}
