package net.fornwall.jelf;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.VortexdbgPointer;
import unicorn.Arm64Const;

public class DwarfCursor64 extends DwarfCursor {

    private static final int FP = 29;
    private static final int LR = 30;
    public static final int SP = 31;

    public DwarfCursor64(Emulator<?> emulator) {
        super(new Long[100]);

        for (int i = Arm64Const.UC_ARM64_REG_X0; i <= Arm64Const.UC_ARM64_REG_X28; i++) {
            VortexdbgPointer pointer = VortexdbgPointer.register(emulator, i);
            loc[i-Arm64Const.UC_ARM64_REG_X0] = pointer == null ? 0 : pointer.peer;
        }
        VortexdbgPointer x29 = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_FP);
        VortexdbgPointer x30 = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_LR);
        VortexdbgPointer x31 = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_SP);
        loc[FP] = x29 == null ? 0 : x29.peer;
        loc[LR] = x30 == null ? 0 : x30.peer;
        loc[SP] = x31 == null ? 0 : x31.peer;

        this.cfa = loc[SP];
        this.ip = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_PC).peer;
    }
}
