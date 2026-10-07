package io.pzstorm.storm.event;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnAuthAttemptEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Integration tests verifying networking events can be dispatched and received by handlers. */
class NetworkingEventDispatchTest implements IntegrationTest {

    @Test
    void shouldDispatchOnAuthAttemptEvent() {
        AuthHandler handler = new AuthHandler();
        StormEventDispatcher.registerEventHandler(handler);

        OnAuthAttemptEvent event =
                new OnAuthAttemptEvent("authUser", "172.16.0.1", 99999999999L, 1, true, null, null);
        StormEventDispatcher.dispatchEvent(event);

        Assertions.assertTrue(handler.wasCalled);
        Assertions.assertEquals("authUser", handler.receivedUsername);
        Assertions.assertTrue(handler.receivedAuthorized);
    }

    @Test
    void shouldDispatchAuthFailureEvent() {
        AuthHandler handler = new AuthHandler();
        StormEventDispatcher.registerEventHandler(handler);

        OnAuthAttemptEvent event =
                new OnAuthAttemptEvent(
                        "hacker", "10.0.0.99", 0L, 1, false, "UI_PasswordInvalid", "Cheating");
        StormEventDispatcher.dispatchEvent(event);

        Assertions.assertTrue(handler.wasCalled);
        Assertions.assertFalse(handler.receivedAuthorized);
        Assertions.assertEquals("UI_PasswordInvalid", handler.receivedDcReason);
        Assertions.assertEquals("Cheating", handler.receivedBannedReason);
    }

    // ---- Test handlers ----

    public static class AuthHandler {
        boolean wasCalled = false;
        String receivedUsername;
        boolean receivedAuthorized;
        String receivedDcReason;
        String receivedBannedReason;

        @SubscribeEvent
        public void onAuth(OnAuthAttemptEvent event) {
            wasCalled = true;
            receivedUsername = event.username;
            receivedAuthorized = event.authorized;
            receivedDcReason = event.dcReason;
            receivedBannedReason = event.bannedReason;
        }
    }
}
