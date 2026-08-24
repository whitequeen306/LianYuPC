package com.lianyu.admin.release;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseValidationServiceTest {
    private final ReleaseValidationService service = new ReleaseValidationService();
    @Test void normalizesSemverAndChannel() { assertEquals("0.2.363", service.normalizeVersion("v0.2.363")); assertEquals("beta", service.validateChannel("BETA")); }
    @Test void rejectsInvalidPackageMetadata() { assertThrows(IllegalArgumentException.class, () -> service.normalizeVersion("latest")); assertThrows(IllegalArgumentException.class, () -> service.validateChannel("nightly")); assertThrows(IllegalArgumentException.class, () -> service.validatePackage("bad.exe", 3)); }
}
