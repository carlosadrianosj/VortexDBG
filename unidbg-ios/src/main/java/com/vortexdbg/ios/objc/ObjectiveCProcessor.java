package com.vortexdbg.ios.objc;

import com.vortexdbg.Symbol;
import com.vortexdbg.ios.MachOModule;

public interface ObjectiveCProcessor {

    Symbol findObjcSymbol(Symbol bestSymbol, long targetAddress, MachOModule module);

}
