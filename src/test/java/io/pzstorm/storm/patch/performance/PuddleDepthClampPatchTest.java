package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

/**
 * Verifies the patched {@code IsoPuddles} bytecode brackets {@code renderSome(int, int, boolean)}
 * with the depth clamp hooks, only there, with the vanilla draw still in place.
 */
class PuddleDepthClampPatchTest implements UnitTest {

    private static final String TARGET = "zombie/iso/IsoPuddles";
    private static final String EARLY_Z = "io/pzstorm/storm/advice/puddleearlyz/PuddleEarlyZ";
    private static final String RENDER_SOME = "renderSome(IIZ)I";

    @Test
    void patchBracketsOnlyRenderSome() throws Exception {
        PuddlePatchScan scan = PuddlePatchScan.weave(new PuddleDepthClampPatch(), TARGET);

        assertTrue(scan.hasMethod(RENDER_SOME));
        assertEquals(1, scan.calls(RENDER_SOME, EARLY_Z, "beginClamp"));
        assertEquals(1, scan.calls(RENDER_SOME, EARLY_Z, "endClamp"));
        assertEquals(0, scan.callsOutside(EARLY_Z, RENDER_SOME));
        assertEquals(1, scan.calls(RENDER_SOME, "org/lwjgl/opengl/GL12", "glDrawRangeElements"));
    }
}
