package com.vortexdbg.ios;

public interface Loader {

    boolean isPayloadModule(String path);

    boolean isUseOverrideResolver();

}
