package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneUserLifetimeWarningsTest {
    @Test public void repeatedHeartbeatWarningsAreLimitedToOncePerMinutePerUid() {
        PhoneUserRelay.LifetimeWarnings warnings = new PhoneUserRelay.LifetimeWarnings();
        assertTrue(warnings.shouldReport("contact-hb", 1000));
        for (long now = 1001; now < 61000; now += 166)
            assertFalse(warnings.shouldReport("contact-hb", now));
        assertTrue(warnings.shouldReport("other-hb", 2000));
        assertFalse(warnings.shouldReport("contact-hb", 60999));
        assertTrue(warnings.shouldReport("contact-hb", 61000));
    }

    @Test public void recoveryRemovalAndStopAllowANewDiagnostic() {
        PhoneUserRelay.LifetimeWarnings warnings = new PhoneUserRelay.LifetimeWarnings();
        assertTrue(warnings.shouldReport("user", 1000));
        warnings.remove("user");
        assertTrue(warnings.shouldReport("user", 1001));
        warnings.clear();
        assertTrue(warnings.shouldReport("user", 1002));
    }

    @Test public void warningStateIsBoundedWithoutChangingContactRelayCapacity() {
        PhoneUserRelay.LifetimeWarnings warnings = new PhoneUserRelay.LifetimeWarnings();
        assertTrue(warnings.shouldReport("old", 1000));
        for (int i = 0; i < PhoneUserRelay.CAPACITY; i++)
            assertTrue(warnings.shouldReport("user-" + i, 1000));
        assertTrue(warnings.shouldReport("old", 1001));
        assertFalse(warnings.shouldReport("user-511", 1001));
        assertEquals(512, PhoneUserRelay.CAPACITY);
    }
}
