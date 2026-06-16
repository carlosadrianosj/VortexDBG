package net.fornwall.jelf;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.VortexdbgPointer;
import unicorn.ArmConst;

public class DwarfCursor32 extends DwarfCursor {

    public static final int SP = 13;
    private static final int LR = 14;
    private static final int PC = 15;

    public DwarfCursor32(Emulator<?> emulator) {
        super(new Long[16]);

        for (int i = ArmConst.UC_ARM_REG_R0; i <= ArmConst.UC_ARM_REG_R12; i++) {
            VortexdbgPointer pointer = VortexdbgPointer.register(emulator, i);
            loc[i-ArmConst.UC_ARM_REG_R0] = pointer == null ? 0 : pointer.peer;
        }
        VortexdbgPointer r13 = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R13);
        VortexdbgPointer r14 = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R14);
        VortexdbgPointer r15 = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_R15);
        loc[SP] = r13 == null ? 0 : r13.peer;
        loc[LR] = r14 == null ? 0 : r14.peer;
        loc[PC] = r15 == null ? 0 : r15.peer;

        this.cfa = loc[SP];
        this.ip = loc[PC];
    }
}
