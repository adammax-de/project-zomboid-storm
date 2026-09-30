package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.PvpHitsAlwaysDamageMetrics;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import zombie.network.GameServer;
import zombie.network.fields.hit.WeaponHit;

/**
 * Server-side: every player-on-player hit that reaches the server does damage, gated on {@code
 * Storm.PvpHitsAlwaysDamage} (default off = vanilla).
 *
 * <p>The shooter's client rolls hit chance. Under {@code FirearmUseDamageChance = 3} a failed roll
 * is still sent, as a hit with {@code WeaponHit.ignoreDamage} set, and {@code
 * IsoPlayer.hitConsequences} then skips the body damage. Before 42.21 the packet never carried that
 * flag, so the server damaged the target on every hit it received. Clearing the flag right after
 * {@code PlayerHitPlayerPacket.parse} restores that, so the server's own hit, the relay to other
 * clients, and the combat log all see a normal hit. Zombie and animal hits use other packets and
 * are untouched.
 */
public final class PvpHitsAlwaysDamage {

    public static final boolean DEFAULT_ENABLED = false;

    private static final long FAILURE_LOG_INTERVAL_MS = 5000L;
    private static final VarHandle IGNORE_DAMAGE = ignoreDamageHandle();

    public static final LongAdder restored = new LongAdder();

    private static volatile boolean enabled = DEFAULT_ENABLED;
    private static long lastFailureLogMs;

    static {
        PvpHitsAlwaysDamageMetrics.init();
    }

    private PvpHitsAlwaysDamage() {}

    /**
     * Applies the {@code Storm.PvpHitsAlwaysDamage} sandbox option and pushes it to the gauge.
     * Single mutation point.
     */
    public static boolean setEnabled(boolean value) {
        enabled = value;
        StormPerformanceSandboxMetrics.setPvpHitsAlwaysDamage(value);
        return value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Runs after {@code PlayerHitPlayerPacket.parse}; acts on the server only. */
    public static void afterParse(List<?> hits) {
        if (!enabled || !GameServer.server) {
            return;
        }
        try {
            int cleared = clearIgnoreDamage(hits);
            if (cleared > 0) {
                restored.add(cleared);
            }
        } catch (Throwable t) {
            logFailure(t);
        }
    }

    /** Clears {@code ignoreDamage} on each hit and returns how many were set. */
    static int clearIgnoreDamage(List<?> hits) {
        if (hits == null || IGNORE_DAMAGE == null) {
            return 0;
        }
        int cleared = 0;
        for (Object hit : hits) {
            if (hit instanceof WeaponHit weaponHit && (boolean) IGNORE_DAMAGE.get(weaponHit)) {
                IGNORE_DAMAGE.set(weaponHit, false);
                cleared++;
            }
        }
        return cleared;
    }

    private static VarHandle ignoreDamageHandle() {
        try {
            return MethodHandles.privateLookupIn(WeaponHit.class, MethodHandles.lookup())
                    .findVarHandle(WeaponHit.class, "ignoreDamage", boolean.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("Storm: WeaponHit.ignoreDamage is gone; PvpHitsAlwaysDamage is inert", e);
            return null;
        }
    }

    private static void logFailure(Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLogMs < FAILURE_LOG_INTERVAL_MS) {
            return;
        }
        lastFailureLogMs = now;
        LOGGER.warn("Storm: PvP hit damage restore failed: {}", t.toString());
    }
}
