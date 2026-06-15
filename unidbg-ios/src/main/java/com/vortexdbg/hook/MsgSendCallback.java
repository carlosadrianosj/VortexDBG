package com.vortexdbg.hook;

import com.vortexdbg.Emulator;
import com.sun.jna.Pointer;

public interface MsgSendCallback {

    /**
     * @return <code>true</code>表示 skip call
     */
    boolean onMsgSend(Emulator<?> emulator, boolean systemClass, String className, String cmd, Pointer lr);

}
