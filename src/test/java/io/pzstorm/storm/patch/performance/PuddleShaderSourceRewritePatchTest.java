package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

/**
 * Verifies the patched {@code ShaderUnit} bytecode passes the result of {@code loadShaderFile}
 * through the puddle rewrite once, only there, with the vanilla loader still in place.
 */
class PuddleShaderSourceRewritePatchTest implements UnitTest {

    private static final String TARGET = "zombie/core/opengl/ShaderUnit";
    private static final String EARLY_Z = "io/pzstorm/storm/advice/puddleearlyz/PuddleEarlyZ";
    private static final String LOAD_SHADER_FILE =
            "loadShaderFile(Ljava/lang/String;Ljava/util/ArrayList;)Ljava/lang/String;";

    @Test
    void patchRewritesOnlyTheResultOfLoadShaderFile() throws Exception {
        PuddlePatchScan scan = PuddlePatchScan.weave(new PuddleShaderSourceRewritePatch(), TARGET);

        assertTrue(scan.hasMethod(LOAD_SHADER_FILE));
        assertEquals(1, scan.calls(LOAD_SHADER_FILE, EARLY_Z, "onShaderSource"));
        assertEquals(0, scan.callsOutside(EARLY_Z, LOAD_SHADER_FILE));
        assertEquals(1, scan.calls(LOAD_SHADER_FILE, TARGET, "preProcessShaderFile"));
    }
}
