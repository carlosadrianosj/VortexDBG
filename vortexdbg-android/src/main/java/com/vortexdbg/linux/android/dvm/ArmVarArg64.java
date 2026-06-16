package com.vortexdbg.linux.android.dvm;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.VortexdbgPointer;
import unicorn.Arm64Const;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class ArmVarArg64 extends ArmVarArg {

    ArmVarArg64(Emulator<?> emulator, BaseVM vm, DvmMethod method) {
        super(emulator, vm, method);

        int offset = 0;
        int floatOff = 0;
        for (Shorty shorty : shorties) {
            switch (shorty.getType()) {
                case 'L':
                case 'B':
                case 'C':
                case 'I':
                case 'S':
                case 'Z': {
                    args.add(getInt(offset++));
                    break;
                }
                case 'D': {
                    args.add(getVectorArg(floatOff++));
                    break;
                }
                case 'F': {
                    args.add((float) getVectorArg(floatOff++));
                    break;
                }
                case 'J': {
                    VortexdbgPointer ptr = getArg(offset++);
                    args.add(ptr == null ? 0L : ptr.peer);
                    break;
                }
                default:
                    throw new IllegalStateException("c=" + shorty.getType());
            }
        }
    }

    private double getVectorArg(int index) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(emulator.getBackend().reg_read_vector(Arm64Const.UC_ARM64_REG_Q0 + index));
        buffer.flip();
        return buffer.getDouble();
    }
}
