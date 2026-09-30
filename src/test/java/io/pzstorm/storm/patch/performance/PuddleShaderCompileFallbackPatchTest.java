package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

/**
 * Verifies the patched {@code ShaderProgram} bytecode brackets {@code compile()} with the enter and
 * exit hooks, only there, with the vanilla compile still in place.
 */
class PuddleShaderCompileFallbackPatchTest implements UnitTest {

    private static final String TARGET = "zombie/core/opengl/ShaderProgram";
    private static final String EARLY_Z = "io/pzstorm/storm/advice/puddleearlyz/PuddleEarlyZ";
    private static final String COMPILE = "compile()V";

    @Test
    void patchBracketsOnlyCompile() throws Exception {
        PuddlePatchScan scan =
                PuddlePatchScan.weave(new PuddleShaderCompileFallbackPatch(), TARGET);

        assertTrue(scan.hasMethod(COMPILE));
        assertEquals(1, scan.calls(COMPILE, EARLY_Z, "onCompileEnter"));
        assertEquals(1, scan.calls(COMPILE, EARLY_Z, "onCompileExit"));
        assertEquals(0, scan.callsOutside(EARLY_Z, COMPILE));
        assertEquals(1, scan.calls(COMPILE, TARGET, "compileAllShaderUnits"));
    }
}
