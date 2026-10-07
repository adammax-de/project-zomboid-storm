package io.pzstorm.storm.metrics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.connection.PeerSendBufferKickConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import zombie.core.raknet.UdpConnection;

/**
 * Pins {@code StormConnectionMetrics.pastJoinGrace}: the send-buffer watchdog leaves a peer alone
 * for {@code PeerSendBufferKickConfig.JOIN_GRACE_MS} after spawn, so the spawn-time sync burst
 * cannot get a healthy peer kicked.
 *
 * <p>{@code UdpConnection} is allocated without running its constructor, for the reason given in
 * {@link StormConnectionMetricsLabelTest}.
 */
class StormConnectionMetricsJoinGraceTest implements UnitTest {

    private static final long GRACE_MS = PeerSendBufferKickConfig.JOIN_GRACE_MS;
    private static final long VANILLA_GRACE_MS = 75_000L;

    @Test
    void aPeerThatHasNotFinishedJoiningIsInGrace() throws Exception {
        assertFalse(StormConnectionMetrics.pastJoinGrace(connection(false, 10 * GRACE_MS)));
    }

    @Test
    void aPeerThatJustSpawnedIsInGrace() throws Exception {
        assertFalse(StormConnectionMetrics.pastJoinGrace(connection(true, 0L)));
    }

    @Test
    void aPeerPastVanillaGraceIsStillInGrace() throws Exception {
        assertFalse(
                StormConnectionMetrics.pastJoinGrace(connection(true, VANILLA_GRACE_MS + 30_000L)));
    }

    @Test
    void aPeerJustInsideTheGraceIsInGrace() throws Exception {
        assertFalse(StormConnectionMetrics.pastJoinGrace(connection(true, GRACE_MS - 5_000L)));
    }

    @Test
    void aPeerJustPastTheGraceIsPastGrace() throws Exception {
        assertTrue(StormConnectionMetrics.pastJoinGrace(connection(true, GRACE_MS + 5_000L)));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A connection stamped the way {@code setFullyConnected()} would have {@code spawnedAgoMs} ago.
     */
    private static UdpConnection connection(boolean fullyConnected, long spawnedAgoMs)
            throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        UdpConnection c = (UdpConnection) allocate.invoke(theUnsafe.get(null), UdpConnection.class);

        Field connected = UdpConnection.class.getDeclaredField("fullyConnected");
        connected.setAccessible(true);
        connected.setBoolean(c, fullyConnected);

        c.connectionTimestamp =
                System.currentTimeMillis()
                        - spawnedAgoMs
                        + StormConnectionMetrics.SPAWN_STAMP_LEAD_MS;
        return c;
    }
}
