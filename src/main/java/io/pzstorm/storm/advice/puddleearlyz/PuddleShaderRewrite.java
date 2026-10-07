package io.pzstorm.storm.advice.puddleearlyz;

/**
 * In-memory rewrite of the two shared puddle shader units. Pure string logic with no game types.
 *
 * <p>The vertex rewrite makes the rasterizer produce the puddle depth, by setting the clip-space z
 * from the depth attribute. The fragment rewrite removes the three {@code gl_FragDepth} writes,
 * because one static {@code gl_FragDepth} assignment anywhere in a fragment shader stops the GPU
 * from depth-testing before it shades.
 *
 * <p>Each rewrite applies only when its anchors match exactly. On any mismatch the method returns
 * the same {@code String} instance it was given, so callers detect a rewrite by identity.
 */
public final class PuddleShaderRewrite {

    static final String POSITION_LINE =
            "gl_Position = ModelViewProjection * vec4(vertex.xy, 0, 1);";
    static final String DEPTH_ATTRIBUTE_LINE = "layout (location = 6) in float aFragDepth;";
    static final String DEPTH_FROM_VERTEX_LINE =
            "gl_Position.z = (2.0 * aFragDepth - 1.0) * gl_Position.w;";
    static final String FRAG_DEPTH_LINE = "gl_FragDepth = vDepth;";
    static final String FRAG_DEPTH_TOKEN = "gl_FragDepth";

    private static final int FRAG_DEPTH_LINES = 3;

    private PuddleShaderRewrite() {}

    /**
     * Inserts the clip-space depth line after the position line. Needs exactly one position line
     * and exactly one depth attribute line, and leaves an already rewritten text alone.
     */
    public static String rewriteVertex(String source) {
        if (source == null
                || source.contains(DEPTH_FROM_VERTEX_LINE)
                || countLines(source, POSITION_LINE) != 1
                || countLines(source, DEPTH_ATTRIBUTE_LINE) != 1) {
            return source;
        }
        StringBuilder out =
                new StringBuilder(source.length() + DEPTH_FROM_VERTEX_LINE.length() + 2);
        int start = 0;
        int length = source.length();
        while (start < length) {
            int end = lineEnd(source, start);
            out.append(source, start, end);
            if (lineEquals(source, start, end, POSITION_LINE)) {
                String terminator = terminator(source, start, end);
                if (terminator.isEmpty()) {
                    out.append('\n');
                }
                out.append(DEPTH_FROM_VERTEX_LINE).append(terminator);
            }
            start = end;
        }
        return out.toString();
    }

    /**
     * Blanks the three {@code gl_FragDepth = vDepth;} lines, keeping their line breaks so compiler
     * messages still point at the right lines. Needs exactly three such lines and no other use of
     * {@code gl_FragDepth}.
     */
    public static String rewriteFragment(String source) {
        if (source == null
                || countLines(source, FRAG_DEPTH_LINE) != FRAG_DEPTH_LINES
                || countOccurrences(source, FRAG_DEPTH_TOKEN) != FRAG_DEPTH_LINES) {
            return source;
        }
        StringBuilder out = new StringBuilder(source.length());
        int start = 0;
        int length = source.length();
        while (start < length) {
            int end = lineEnd(source, start);
            if (lineEquals(source, start, end, FRAG_DEPTH_LINE)) {
                out.append(terminator(source, start, end));
            } else {
                out.append(source, start, end);
            }
            start = end;
        }
        return out.toString();
    }

    /** Index just past the line that starts at {@code start}, including its line break. */
    private static int lineEnd(String source, int start) {
        int newline = source.indexOf('\n', start);
        return newline < 0 ? source.length() : newline + 1;
    }

    private static String terminator(String source, int start, int end) {
        if (end > start && source.charAt(end - 1) == '\n') {
            return end - 1 > start && source.charAt(end - 2) == '\r' ? "\r\n" : "\n";
        }
        return "";
    }

    private static boolean lineEquals(String source, int start, int end, String expected) {
        return source.substring(start, end).trim().equals(expected);
    }

    private static int countLines(String source, String expected) {
        int count = 0;
        int start = 0;
        int length = source.length();
        while (start < length) {
            int end = lineEnd(source, start);
            if (lineEquals(source, start, end, expected)) {
                count++;
            }
            start = end;
        }
        return count;
    }

    private static int countOccurrences(String source, String token) {
        int count = 0;
        int index = source.indexOf(token);
        while (index >= 0) {
            count++;
            index = source.indexOf(token, index + token.length());
        }
        return count;
    }
}
