package com.lianyu.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class AdminModuleSmokeTest {
    @Test
    void markerLoads() {
        assertNotNull(AdminModuleMarker.class);
    }
}
