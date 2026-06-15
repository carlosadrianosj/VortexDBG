package com.vortexdbg.arm.backend.kvm;

import com.vortexdbg.arm.backend.Backend;
import com.vortexdbg.arm.backend.BackendFactory;
import com.vortexdbg.arm.backend.KvmFactory;
import junit.framework.TestCase;

import java.util.Collections;

public class KvmTest extends TestCase {

    public void testBackend() {
        Backend backend = BackendFactory.createBackend(null, true, Collections.singleton(new KvmFactory(false)));
        assertNotNull(backend);
        backend.destroy();
    }

}
