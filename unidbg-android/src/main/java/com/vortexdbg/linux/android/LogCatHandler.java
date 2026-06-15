package com.vortexdbg.linux.android;

public interface LogCatHandler {

    void handleLog(String type, LogCatLevel level, String tag, String text);

}
