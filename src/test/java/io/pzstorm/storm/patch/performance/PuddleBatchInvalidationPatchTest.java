package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

/**
 * Verifies the patched {@code FBORenderLevels} bytecode calls the rebuild hook from {@code
 * clearCachedSquares(int)} and the light hook from {@code invalidateLevel(int, long)}, once each
 * and nowhere else.
 */
class PuddleBatchInvalidationPatchTest implements UnitTest {

    private static final String TARGET = "zombie/iso/fboRenderChunk/FBORenderLevels";
    private static final String HOOKS = "io/pzstorm/storm/advice/puddlebatch/PuddleBatchHooks";
    private static final String CLEAR_CACHED_SQUARES = "clearCachedSquares(I)V";
    private static final String INVALIDATE_LEVEL = "invalidateLevel(IJ)V";

    @Test
    void patchHooksExactlyTheTwoInvalidationMethods() throws Exception {
        PuddlePatchScan scan = PuddlePatchScan.weave(new PuddleBatchInvalidationPatch(), TARGET);

        assertTrue(scan.hasMethod(CLEAR_CACHED_SQUARES));
        assertTrue(scan.hasMethod(INVALIDATE_LEVEL));
        assertEquals(1, scan.calls(CLEAR_CACHED_SQUARES, HOOKS, "onClearCachedSquares"));
        assertEquals(0, scan.calls(CLEAR_CACHED_SQUARES, HOOKS, "onInvalidateLevel"));
        assertEquals(1, scan.calls(INVALIDATE_LEVEL, HOOKS, "onInvalidateLevel"));
        assertEquals(0, scan.calls(INVALIDATE_LEVEL, HOOKS, "onClearCachedSquares"));
        assertEquals(0, scan.callsOutside(HOOKS, CLEAR_CACHED_SQUARES, INVALIDATE_LEVEL));
    }
}
