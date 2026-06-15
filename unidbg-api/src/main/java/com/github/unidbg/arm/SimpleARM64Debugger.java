package com.github.unidbg.arm;

import com.github.unidbg.Emulator;

import com.github.unidbg.Module;

import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.BackendException;
import com.github.unidbg.debugger.DebugRunnable;
import com.github.unidbg.debugger.Debugger;
import com.github.unidbg.debugger.FunctionCallListener;


import com.github.unidbg.thread.RunnableTask;

import keystone.Keystone;
import keystone.KeystoneArchitecture;
import keystone.KeystoneMode;
import org.apache.commons.codec.DecoderException;

import unicorn.Arm64Const;

import java.util.Scanner;

class SimpleARM64Debugger extends AbstractARMDebugger implements Debugger {

    SimpleARM64Debugger(Emulator<?> emulator) {
        super(emulator);
    }

    @Override
    public void traceFunctionCall(Module module, FunctionCallListener listener) {
        Backend backend = emulator.getBackend();
        TraceFunctionCall hook = new TraceFunctionCall64(emulator, listener);
        long begin = module == null ? 1 : module.base;
        long end = module == null ? 0 : module.base + module.size;
        backend.hook_add_new(hook, begin, end, emulator);
    }

    @Override
    protected final void loop(Emulator<?> emulator, long address, int size, DebugRunnable<?> runnable) throws Exception {
        Backend backend = emulator.getBackend();
        long nextAddress = 0;

        try {
            if (address != -1) {
                RunnableTask runningTask = emulator.getThreadDispatcher().getRunningTask();
                System.out.println("debugger break at: 0x" + Long.toHexString(address) + (runningTask == null ? "" : (" @ " + runningTask)));
                emulator.showRegs();
            }
            if (address > 0) {
                nextAddress = disassemble(emulator, address, size, false);
            }
        } catch (BackendException e) {
            e.printStackTrace(System.err);
        }

        Scanner scanner = new Scanner(System.in);
        String line;
        while ((line = scanner.nextLine()) != null) {
            line = line.trim();
            try {
                if ("d".equals(line) || "dis".equals(line)) {
                    emulator.showRegs();
                    disassemble(emulator, address, size, false);
                    continue;
                }
                if (line.startsWith("d0x")) {
                    if (line.endsWith("L")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    long da = Long.parseLong(line.substring(3), 16);
                    disassembleBlock(emulator, da & 0xfffffffffffffffcL, false);
                    continue;
                }
                if (handleWriteCommand(backend, line)) {
                    continue;
                }
                if(handleCommon(backend, line, address, size, nextAddress, runnable)) {
                    break;
                }
                if (scannerNeedsRefresh) {
                    scanner = new Scanner(System.in);
                    scannerNeedsRefresh = false;
                }
            } catch (RuntimeException | DecoderException e) {
                e.printStackTrace(System.err);
            }
        }
    }

    @Override
    final void showHelp(long address) {
        super.showHelp(address);
        System.out.println("s(bl): execute util BL mnemonic, low performance");
        System.out.println();
        System.out.println("m(op) [size]: show memory, default size is 0x70, size may hex or decimal");
        System.out.println("mx0-mx28, mfp, mip, msp [size]: show memory of specified register");
        System.out.println("m(address) [size]: show memory of specified address, address must start with 0x");
        System.out.println("  append 's' to read as C string, e.g. mx0s, m0x1234s");
        System.out.println("  append 'std' to read as std::string, e.g. mx0std, m0x1234std");
        System.out.println("  append 'objc' to read ObjC class name, e.g. mx0objc, m0x1234objc");
        System.out.println();
        System.out.println("wx0-wx28, wfp, wip, wsp <value>: write specified register");
        System.out.println("wb(address), ws(address), wi(address), wl(address) <value>: write (byte, short, integer, long) memory of specified address, address must start with 0x");
        showCommonHelp(address);
    }

    @Override
    protected int resolveWriteRegister(String command) {
        if (command.startsWith("wx") && (command.length() == 3 || command.length() == 4)) {
            int idx = Integer.parseInt(command.substring(2));
            if (idx >= 0 && idx <= 28) {
                return Arm64Const.UC_ARM64_REG_X0 + idx;
            }
        } else if ("wfp".equals(command)) {
            return Arm64Const.UC_ARM64_REG_FP;
        } else if ("wip".equals(command)) {
            return Arm64Const.UC_ARM64_REG_IP0;
        } else if ("wsp".equals(command)) {
            return Arm64Const.UC_ARM64_REG_SP;
        }
        return -1;
    }

    @Override
    protected void showWriteRegs(int reg) {
        ARM.showRegs64(emulator, new int[] { reg });
    }

    @Override
    protected void showWriteHelp() {
        System.out.println("wx0-wx28, wfp, wip, wsp <value>: write specified register");
        System.out.println("wb(address), ws(address), wi(address), wl(address) <value>: write (byte, short, integer, long) memory of specified address, address must start with 0x");
    }

    @Override
    protected int resolveRegister(String command, String[] nameOut) {
        if (command.startsWith("mx") && (command.length() == 3 || command.length() == 4)) {
            int idx = Integer.parseInt(command.substring(2));
            if (idx >= 0 && idx <= 28) {
                nameOut[0] = "x" + idx;
                return Arm64Const.UC_ARM64_REG_X0 + idx;
            }
        } else if ("mfp".equals(command)) {
            nameOut[0] = "fp";
            return Arm64Const.UC_ARM64_REG_FP;
        } else if ("mip".equals(command)) {
            nameOut[0] = "ip";
            return Arm64Const.UC_ARM64_REG_IP0;
        } else if ("msp".equals(command)) {
            nameOut[0] = "sp";
            return Arm64Const.UC_ARM64_REG_SP;
        }
        return -1;
    }

    @Override
    protected Keystone createKeystone(boolean isThumb) {
        return new Keystone(KeystoneArchitecture.Arm64, KeystoneMode.LittleEndian);
    }

}
