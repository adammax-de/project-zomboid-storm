package io.pzstorm.storm.metrics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import zombie.core.raknet.UdpConnection;

/**
 * Pins {@code StormConnectionMetrics.pastJoinGrace}: the send-buffer watchdog leaves a peer alone
 * until vanilla's post-join grace has run out, so the spawn-time sync burst cannot get a healthy
 * peer kicked.
 *
 * <p>{@code UdpConnection} is allocated without running its constructor, for the reason given in
 * {@link StormConnectionMetricsLabelTest}.
 */
class StormConnectionMetricsJoinGraceTest implements UnitTest {

    private static final long GRACE_MS = 60_000L;

    @Test
    void aPeerThatHasNotFinishedJoiningIsInGrace() throws Exception {
        long longAgo = System.currentTimeMillis() - 10 * GRACE_MS;
        assertFalse(StormConnectionMetrics.pastJoinGrace(connection(false, longAgo)));
    }

    @Test
    void aPeerThatJustSpawnedIsInGrace() throws Exception {
        long justSpawned = System.currentTimeMillis() + 15_000L;
        assertFalse(StormConnectionMetrics.pastJoinGrace(connection(true, justSpawned)));
    }

    @Test
    void aPeerTwentySecondsAfterSpawnIsInGrace() throws Exception {
        long spawnedTwentySecondsAgo = System.currentTimeMillis() + 15_000L - 20_000L;
        assertFalse(
                StormConnectionMetrics.pastJoinGrace(connection(true, spawnedTwentySecondsAgo)));
    }

    @Test
    void aLongConnectedPeerIsPastGrace() throws Exception {
        long longAgo = System.currentTimeMillis() - 2 * GRACE_MS;
        assertTrue(StormConnectionMetrics.pastJoinGrace(connection(true, longAgo)));
    }

    // ------------------------------------------------------------------ helpers

    private static UdpConnection connection(boolean fullyConnected, long connectionTimestamp)
            throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        UdpConnection c = (UdpConnection) allocate.invoke(theUnsafe.get(null), UdpConnection.class);

        Field connected = UdpConnection.class.getDeclaredField("fullyConnected");
        connected.setAccessible(true);
        connected.setBoolean(c, fullyConnected);

        c.connectionTimestamp = connectionTimestamp;
        return c;
    }
}
