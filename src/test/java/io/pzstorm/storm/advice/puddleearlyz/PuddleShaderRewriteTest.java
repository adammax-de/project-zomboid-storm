package io.pzstorm.storm.advice.puddleearlyz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link PuddleShaderRewrite} with synthetic shader snippets. Only the anchor lines match
 * the game; the rest is made up for the test.
 */
class PuddleShaderRewriteTest implements UnitTest {

    private static final String POSITION = PuddleShaderRewrite.POSITION_LINE;
    private static final String ATTRIBUTE = PuddleShaderRewrite.DEPTH_ATTRIBUTE_LINE;
    private static final String INSERTED = PuddleShaderRewrite.DEPTH_FROM_VERTEX_LINE;
    private static final String FRAG_DEPTH = PuddleShaderRewrite.FRAG_DEPTH_LINE;

    private static String vertex(String eol) {
        return String.join(
                eol,
                "#version 330",
                "layout (location = 0) in vec2 vertex;",
                ATTRIBUTE,
                "uniform mat4 ModelViewProjection;",
                "out float vDepth;",
                "void main()",
                "{",
                "\t" + POSITION,
                "\tvDepth = aFragDepth;",
                "}",
                "");
    }

    private static String fragment(String eol, int depthWrites) {
        StringBuilder out = new StringBuilder();
        out.append("#version 120").append(eol);
        out.append("varying float vDepth;").append(eol);
        for (int i = 0; i < depthWrites; i++) {
            out.append("void shade").append(i).append("()").append(eol);
            out.append("{").append(eol);
            out.append("\tgl_FragColor = vec4(").append(i).append(".0);").append(eol);
            out.append("\t").append(FRAG_DEPTH).append(eol);
            out.append("}").append(eol);
        }
        return out.toString();
    }

    private static int lineCount(String text) {
        return text.split("\n", -1).length;
    }

    @Test
    void vertexRewriteInsertsTheDepthLineAfterThePositionLine() {
        String source = vertex("\n");

        String rewritten = PuddleShaderRewrite.rewriteVertex(source);

        assertNotSame(source, rewritten);
        assertEquals(
                source.replace("\t" + POSITION + "\n", "\t" + POSITION + "\n" + INSERTED + "\n"),
                rewritten);
        assertEquals(lineCount(source) + 1, lineCount(rewritten));
    }

    @Test
    void vertexRewriteKeepsWindowsLineEnds() {
        String source = vertex("\r\n");

        String rewritten = PuddleShaderRewrite.rewriteVertex(source);

        assertTrue(rewritten.contains(POSITION + "\r\n" + INSERTED + "\r\n"));
        assertEquals(
                rewritten.length() - rewritten.replace("\n", "").length(),
                rewritten.length() - rewritten.replace("\r", "").length(),
                "every line break must stay \\r\\n");
    }

    @Test
    void vertexRewriteHandlesAPositionLineWithNoLineBreak() {
        String source = ATTRIBUTE + "\n" + POSITION;

        assertEquals(source + "\n" + INSERTED, PuddleShaderRewrite.rewriteVertex(source));
    }

    @Test
    void vertexRewriteIsIdempotent() {
        String once = PuddleShaderRewrite.rewriteVertex(vertex("\n"));

        assertSame(once, PuddleShaderRewrite.rewriteVertex(once));
    }

    @Test
    void vertexRewriteNeedsExactlyOnePositionLine() {
        String none = vertex("\n").replace(POSITION, "gl_Position = vec4(vertex.xy, 0, 1);");
        String two = vertex("\n").replace("\t" + POSITION, "\t" + POSITION + "\n\t" + POSITION);

        assertSame(none, PuddleShaderRewrite.rewriteVertex(none));
        assertSame(two, PuddleShaderRewrite.rewriteVertex(two));
    }

    @Test
    void vertexRewriteNeedsExactlyOneDepthAttributeLine() {
        String none = vertex("\n").replace(ATTRIBUTE, "attribute float aFragDepth;");
        String two = vertex("\n").replace(ATTRIBUTE, ATTRIBUTE + "\n" + ATTRIBUTE);

        assertSame(none, PuddleShaderRewrite.rewriteVertex(none));
        assertSame(two, PuddleShaderRewrite.rewriteVertex(two));
    }

    @Test
    void vertexRewriteIgnoresAnAnchorThatIsOnlyPartOfALine() {
        String source = vertex("\n").replace(POSITION, POSITION + " // trailing");

        assertSame(source, PuddleShaderRewrite.rewriteVertex(source));
    }

    @Test
    void fragmentRewriteBlanksAllThreeDepthWrites() {
        String source = fragment("\n", 3);

        String rewritten = PuddleShaderRewrite.rewriteFragment(source);

        assertNotSame(source, rewritten);
        assertFalse(rewritten.contains("gl_FragDepth"));
        assertEquals(source.replace("\t" + FRAG_DEPTH + "\n", "\n"), rewritten);
        assertEquals(lineCount(source), lineCount(rewritten), "line numbers must not move");
        assertTrue(rewritten.contains("varying float vDepth;"), "vDepth stays declared");
    }

    @Test
    void fragmentRewriteKeepsWindowsLineEnds() {
        String source = fragment("\r\n", 3);

        String rewritten = PuddleShaderRewrite.rewriteFragment(source);

        assertEquals(source.replace("\t" + FRAG_DEPTH + "\r\n", "\r\n"), rewritten);
    }

    @Test
    void fragmentRewriteLeavesZeroTwoOrFourDepthWritesAlone() {
        for (int writes : new int[] {0, 1, 2, 4}) {
            String source = fragment("\n", writes);

            assertSame(source, PuddleShaderRewrite.rewriteFragment(source), writes + " writes");
        }
    }

    @Test
    void fragmentRewriteLeavesAnyOtherUseOfFragDepthAlone() {
        String extraWrite = fragment("\n", 3) + "void other() { gl_FragDepth = 0.5; }\n";
        String conditional =
                fragment("\n", 2) + "void other()\n{\nif (vDepth > 0.0) " + FRAG_DEPTH + "\n}\n";
        String read = fragment("\n", 3) + "float peek() { return gl_FragDepth; }\n";

        assertSame(extraWrite, PuddleShaderRewrite.rewriteFragment(extraWrite));
        assertSame(conditional, PuddleShaderRewrite.rewriteFragment(conditional));
        assertSame(read, PuddleShaderRewrite.rewriteFragment(read));
    }

    @Test
    void fragmentRewriteIsIdempotent() {
        String once = PuddleShaderRewrite.rewriteFragment(fragment("\n", 3));

        assertSame(once, PuddleShaderRewrite.rewriteFragment(once));
    }

    @Test
    void nullTextPassesThrough() {
        assertNull(PuddleShaderRewrite.rewriteVertex(null));
        assertNull(PuddleShaderRewrite.rewriteFragment(null));
    }
}
