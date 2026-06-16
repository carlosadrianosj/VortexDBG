package com.vortexdbg.hook.xhook;

import com.vortexdbg.hook.IHook;
import com.vortexdbg.hook.ReplaceCallback;

/**
 * Only support android
 */
public interface IxHook extends IHook {

    int RET_SUCCESS = 0;

    void register(String pathname_regex_str, String symbol, ReplaceCallback callback);
    void register(String pathname_regex_str, String symbol, ReplaceCallback callback, boolean enablePostCall);

    void refresh();

}
