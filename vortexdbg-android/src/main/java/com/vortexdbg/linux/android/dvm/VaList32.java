package com.vortexdbg.linux.android.dvm;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.VortexdbgPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

class VaList32 extends VaList {

    private static final Logger log = LoggerFactory.getLogger(VaList32.class);

    VaList32(Emulator<?> emulator, BaseVM vm, VortexdbgPointer va_list, DvmMethod method) {
        super(vm, method);

        VortexdbgPointer pointer = va_list;
        for (Shorty shorty : shorties) {
            switch (shorty.getType()) {
                case 'L':
                case 'B':
                case 'C':
                case 'I':
                case 'S':
                case 'Z': {
                    args.add(pointer.getInt(0));
                    pointer = pointer.share(4, 0);
                    break;
                }
                case 'D': {
                    VortexdbgPointer ptr = VortexdbgPointer.pointer(emulator, (pointer.toUIntPeer() + 7) & 0xfffffff8L);
                    assert ptr != null;
                    args.add(ptr.getDouble(0));
                    pointer = ptr.share(8, 0);
                    break;
                }
                case 'F': {
                    VortexdbgPointer ptr = VortexdbgPointer.pointer(emulator, (pointer.toUIntPeer() + 7) & 0xfffffff8L);
                    assert ptr != null;
                    args.add((float) ptr.getDouble(0));
                    pointer = ptr.share(8, 0);
                    break;
                }
                case 'J': {
                    VortexdbgPointer ptr = VortexdbgPointer.pointer(emulator, (pointer.toUIntPeer() + 7) & 0xfffffff8L);
                    assert ptr != null;
                    args.add(ptr.getLong(0));
                    pointer = ptr.share(8, 0);
                    break;
                }
                default:
                    throw new IllegalStateException("c=" + shorty.getType());
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("VaList64 args={}, shorty={}", method.args, Arrays.toString(shorties));
        }
    }
}
