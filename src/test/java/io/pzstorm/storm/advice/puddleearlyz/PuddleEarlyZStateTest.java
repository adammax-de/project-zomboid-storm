package io.pzstorm.storm.advice.puddleearlyz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link PuddleEarlyZState} through the call order of a {@code ShaderProgram.compile()}:
 * begin, vertex unit, fragment unit, end. The shader texts are synthetic.
 */
class PuddleEarlyZStateTest implements UnitTest {

    private static final String VERTEX_FILE = "media/shaders/puddles_common.vert.glsl";
    private static final String FRAGMENT_FILE = "media/shaders/puddles_common.frag.glsl";

    private static final String VERTEX =
            PuddleShaderRewrite.DEPTH_ATTRIBUTE_LINE
                    + "\nvoid main()\n{\n"
                    + PuddleShaderRewrite.POSITION_LINE
                    + "\n}\n";
    private static final String FRAGMENT =
            "void a()\n{\n"
                    + PuddleShaderRewrite.FRAG_DEPTH_LINE
                    + "\n}\nvoid b()\n{\n"
                    + PuddleShaderRewrite.FRAG_DEPTH_LINE
                    + "\n}\nvoid c()\n{\n"
                    + PuddleShaderRewrite.FRAG_DEPTH_LINE
                    + "\n}\n";

    private PuddleEarlyZState state;

    @BeforeEach
    void setUp() {
        state = new PuddleEarlyZState();
    }

    @Test
    void bothUnitsAreRewrittenInsideOneCompile() {
        int serial = state.beginCompile();

        assertNotEquals(0, serial);
        assertNotSame(VERTEX, state.rewrite(VERTEX_FILE, VERTEX, true));
        assertNotSame(FRAGMENT, state.rewrite(FRAGMENT_FILE, FRAGMENT, true));
        assertFalse(state.isVertexRewriteLive(), "not live before the program compiled");

        assertFalse(state.endCompile(serial, "puddles_hq", true));
        assertTrue(state.isVertexRewriteLive());
        assertFalse(state.isLatched());
    }

    @Test
    void nothingIsRewrittenOutsideACompile() {
        assertSame(VERTEX, state.rewrite(VERTEX_FILE, VERTEX, true));
        assertSame(FRAGMENT, state.rewrite(FRAGMENT_FILE, FRAGMENT, true));

        int serial = state.beginCompile();
        state.endCompile(serial, "puddles_hq", true);

        assertSame(VERTEX, state.rewrite(VERTEX_FILE, VERTEX, true));
    }

    @Test
    void nothingIsRewrittenWhenRequirementsAreMissing() {
        int serial = state.beginCompile();

        assertSame(VERTEX, state.rewrite(VERTEX_FILE, VERTEX, false));
        assertSame(FRAGMENT, state.rewrite(FRAGMENT_FILE, FRAGMENT, false));
        assertFalse(state.endCompile(serial, "puddles_hq", true));
        assertFalse(state.isVertexRewriteLive());
    }

    @Test
    void otherFilesPassThrough() {
        state.beginCompile();

        assertSame(VERTEX, state.rewrite("media/shaders/water_common.vert.glsl", VERTEX, true));
        assertSame(VERTEX, state.rewrite("media/shaders/puddles_hq.vert", VERTEX, true));
        assertFalse(PuddleEarlyZState.isPuddleUnit("media/shaders/puddles_hq.vert"));
        assertTrue(PuddleEarlyZState.isPuddleUnit(VERTEX_FILE));
        assertTrue(PuddleEarlyZState.isPuddleUnit(FRAGMENT_FILE));
        assertFalse(PuddleEarlyZState.isPuddleUnit(null));
    }

    @Test
    void fragmentStaysVanillaWhenTheVertexAnchorsMissed() {
        String changedVertex = VERTEX.replace("vec4(vertex.xy, 0, 1)", "vec4(vertex.xy, 0.0, 1.0)");
        int serial = state.beginCompile();

        assertSame(changedVertex, state.rewrite(VERTEX_FILE, changedVertex, true));
        assertSame(FRAGMENT, state.rewrite(FRAGMENT_FILE, FRAGMENT, true));
        assertFalse(state.endCompile(serial, "puddles_hq", true));
        assertFalse(state.isVertexRewriteLive());
    }

    @Test
    void fragmentStaysVanillaWhenTheVertexWasRewrittenInAnEarlierCompile() {
        int first = state.beginCompile();
        state.rewrite(VERTEX_FILE, VERTEX, true);
        state.endCompile(first, "puddles_hq", true);

        state.beginCompile();

        assertSame(FRAGMENT, state.rewrite(FRAGMENT_FILE, FRAGMENT, true));
    }

    @Test
    void vertexAloneStaysRewrittenWhenTheFragmentAnchorsMissed() {
        String changedFragment = FRAGMENT + "void d() { gl_FragDepth = 0.0; }\n";
        int serial = state.beginCompile();

        assertNotSame(VERTEX, state.rewrite(VERTEX_FILE, VERTEX, true));
        assertSame(changedFragment, state.rewrite(FRAGMENT_FILE, changedFragment, true));
        assertFalse(state.endCompile(serial, "puddles_mq", true));
        assertTrue(state.isVertexRewriteLive(), "the depth clamp must follow the vertex rewrite");
    }

    @Test
    void aFailedRewrittenCompileLatchesAndAsksForARecompile() {
        int serial = state.beginCompile();
        state.rewrite(VERTEX_FILE, VERTEX, true);
        state.rewrite(FRAGMENT_FILE, FRAGMENT, true);

        assertTrue(state.endCompile(serial, "puddles_hq", false));
        assertTrue(state.isLatched());
        assertFalse(state.isVertexRewriteLive());

        int retry = state.beginCompile();
        assertSame(VERTEX, state.rewrite(VERTEX_FILE, VERTEX, true));
        assertSame(FRAGMENT, state.rewrite(FRAGMENT_FILE, FRAGMENT, true));
        assertFalse(state.endCompile(retry, "puddles_hq", true));
        assertFalse(state.isVertexRewriteLive());
    }

    @Test
    void aFailedVanillaCompileDoesNotLatch() {
        int serial = state.beginCompile();

        assertFalse(state.endCompile(serial, "puddles_hq", false));
        assertFalse(state.isLatched());

        int other = state.beginCompile();
        assertFalse(state.endCompile(other, "basicEffect", false));
        assertFalse(state.isLatched());
    }

    @Test
    void theClampStaysLiveWhileAnyRewrittenProgramRemains() {
        int hq = state.beginCompile();
        state.rewrite(VERTEX_FILE, VERTEX, true);
        state.rewrite(FRAGMENT_FILE, FRAGMENT, true);
        state.endCompile(hq, "puddles_hq", true);

        int mq = state.beginCompile();
        state.rewrite(VERTEX_FILE, VERTEX, true);
        state.rewrite(FRAGMENT_FILE, FRAGMENT, true);
        assertTrue(state.endCompile(mq, "puddles_mq", false));
        assertTrue(state.isVertexRewriteLive(), "puddles_hq still carries the rewrite");

        int hqAgain = state.beginCompile();
        state.rewrite(VERTEX_FILE, VERTEX, true);
        assertFalse(state.endCompile(hqAgain, "puddles_hq", true));
        assertFalse(state.isVertexRewriteLive(), "puddles_hq compiled again as vanilla");
    }

    @Test
    void latchStopsEveryLaterRewrite() {
        state.latch();
        int serial = state.beginCompile();

        assertSame(VERTEX, state.rewrite(VERTEX_FILE, VERTEX, true));
        assertFalse(state.endCompile(serial, "puddles_hq", true));
        assertFalse(state.isVertexRewriteLive());
    }
}
