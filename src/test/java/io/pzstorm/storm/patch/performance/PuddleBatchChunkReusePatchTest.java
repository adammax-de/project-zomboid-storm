package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

/**
 * Verifies the patched {@code IsoChunk} bytecode calls the chunk-reset hook from {@code
 * resetForStore()} once and nowhere else.
 */
class PuddleBatchChunkReusePatchTest implements UnitTest {

    private static final String TARGET = "zombie/iso/IsoChunk";
    private static final String HOOKS = "io/pzstorm/storm/advice/puddlebatch/PuddleBatchHooks";
    private static final String RESET_FOR_STORE = "resetForStore()V";

    @Test
    void patchHooksOnlyResetForStore() throws Exception {
        PuddlePatchScan scan = PuddlePatchScan.weave(new PuddleBatchChunkReusePatch(), TARGET);

        assertTrue(scan.hasMethod(RESET_FOR_STORE));
        assertEquals(1, scan.calls(RESET_FOR_STORE, HOOKS, "onChunkReset"));
        assertEquals(0, scan.callsOutside(HOOKS, RESET_FOR_STORE));
    }
}
