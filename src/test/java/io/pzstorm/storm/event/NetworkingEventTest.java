package io.pzstorm.storm.event;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.event.lua.OnAuthAttemptEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for networking event construction and field access. */
class NetworkingEventTest implements UnitTest {

    @Test
    void onAuthAttemptEvent_shouldStoreAllFields() {
        OnAuthAttemptEvent event =
                new OnAuthAttemptEvent("authUser", "172.16.0.1", 99999999999L, 1, true, null, null);

        Assertions.assertEquals("authUser", event.username);
        Assertions.assertEquals("172.16.0.1", event.ip);
        Assertions.assertEquals(99999999999L, event.steamId);
        Assertions.assertEquals(1, event.authType);
        Assertions.assertTrue(event.authorized);
        Assertions.assertNull(event.dcReason);
        Assertions.assertNull(event.bannedReason);
    }

    @Test
    void onAuthAttemptEvent_shouldStoreFailureDetails() {
        OnAuthAttemptEvent event =
                new OnAuthAttemptEvent(
                        "banned", "10.0.0.99", 0L, 1, false, "UI_PasswordInvalid", "Cheating");

        Assertions.assertFalse(event.authorized);
        Assertions.assertEquals("UI_PasswordInvalid", event.dcReason);
        Assertions.assertEquals("Cheating", event.bannedReason);
    }
}
