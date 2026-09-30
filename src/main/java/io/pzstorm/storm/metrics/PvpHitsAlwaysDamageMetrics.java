package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.pzstorm.storm.patch.fixes.PvpHitsAlwaysDamage;

/** Metrics for {@code PvpHitsAlwaysDamage}. Registered on first class use. */
public final class PvpHitsAlwaysDamageMetrics {

    @SuppressWarnings("unused")
    private static final CounterWithCallback RESTORED =
            CounterWithCallback.builder()
                    .name("storm_pvp_hits_damage_restored_total")
                    .help(
                            "Player-on-player hits that arrived flagged as zero-damage (the"
                                    + " shooter's failed hit-chance roll) and that the server"
                                    + " applied with full damage.")
                    .callback(
                            callback -> callback.call((double) PvpHitsAlwaysDamage.restored.sum()))
                    .register(StormPrometheus.registry());

    private PvpHitsAlwaysDamageMetrics() {}

    /** Forces registration; called from {@code PvpHitsAlwaysDamage}'s static initializer. */
    public static void init() {}
}
