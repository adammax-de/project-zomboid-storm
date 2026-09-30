package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

/**
 * Verifies the patched {@code FBORenderCell} bytecode routes {@code renderPuddles(int)} through the
 * batch renderer exactly once, only there, and keeps the vanilla body as the fallback the fail-soft
 * latch relies on.
 */
class PuddleBatchRenderPatchTest implements UnitTest {

    private static final String TARGET = "zombie/iso/fboRenderChunk/FBORenderCell";
    private static final String RENDERER =
            "io/pzstorm/storm/advice/puddlebatch/PuddleBatchRenderer";
    private static final String RENDER_PUDDLES = "renderPuddles(I)V";

    @Test
    void patchRoutesOnlyRenderPuddlesThroughTheBatchRenderer() throws Exception {
        PuddlePatchScan scan = PuddlePatchScan.weave(new PuddleBatchRenderPatch(), TARGET);

        assertTrue(scan.hasMethod(RENDER_PUDDLES));
        assertEquals(1, scan.calls(RENDER_PUDDLES, RENDERER, "render"));
        assertEquals(0, scan.callsOutside(RENDERER, RENDER_PUDDLES));

        // The vanilla body must survive: a false return from the renderer falls through to it.
        assertEquals(1, scan.calls(RENDER_PUDDLES, "zombie/iso/IsoPuddles", "render"));
        assertTrue(scan.calls(RENDER_PUDDLES, "zombie/iso/IsoGridSquare", "IsOnScreen") >= 1);
    }
}
