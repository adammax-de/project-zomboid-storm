package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Metrics for {@code ContainerChainGuard}. Main-thread writer only (inventory code runs on the game
 * main loop on both JVMs).
 */
public final class ContainerChainGuardMetrics {

    private static long repairedCycles;
    private static long refusedNestings;

    @SuppressWarnings("unused")
    private static final CounterWithCallback REPAIRED =
            CounterWithCallback.builder()
                    .name("storm_container_chain_cycles_repaired_total")
                    .help(
                            "Container chains found to loop back on themselves (a bag inside its"
                                    + " own contents) during an ItemContainer walk. Vanilla"
                                    + " recurses until StackOverflowError; Storm detaches the"
                                    + " offending item and logs it at WARN.")
                    .callback(callback -> callback.call((double) repairedCycles))
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback REFUSED =
            CounterWithCallback.builder()
                    .name("storm_container_chain_nestings_refused_total")
                    .help(
                            "ItemContainer.AddItem calls refused because they would have placed a"
                                    + " bag inside its own contents. Each refusal is logged at"
                                    + " WARN.")
                    .callback(callback -> callback.call((double) refusedNestings))
                    .register(StormPrometheus.registry());

    private ContainerChainGuardMetrics() {}

    public static void recordRepairedCycle() {
        repairedCycles++;
    }

    public static void recordRefusedNesting() {
        refusedNestings++;
    }
}
