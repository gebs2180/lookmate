package com.lookmate.app;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Module boundaries (identity, storage, notify, and the feature modules as they land): no cycles, no reaching into internals. */
class ModularityTest {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(LookmateApplication.class).verify();
    }
}
