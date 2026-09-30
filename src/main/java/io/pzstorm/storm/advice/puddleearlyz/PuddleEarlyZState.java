package io.pzstorm.storm.advice.puddleearlyz;

import java.util.HashSet;

/**
 * Bookkeeping of the puddle shader rewrite across {@code ShaderProgram.compile()} calls. Holds no
 * game types, so the coupling and fallback rules are unit-testable.
 *
 * <p>Every compile gets a serial. A unit is rewritten only while a compile is in flight, and the
 * fragment unit only when the vertex unit was rewritten under the same serial, because a fragment
 * shader without its depth write needs the vertex shader to supply the depth. A rewritten compile
 * that fails latches the rewrite off for the session and asks the caller to compile once more.
 */
public final class PuddleEarlyZState {

    static final String VERTEX_UNIT = "puddles_common.vert.glsl";
    static final String FRAGMENT_UNIT = "puddles_common.frag.glsl";
    static final String PROGRAM_PREFIX = "puddles_";

    private final HashSet<String> rewrittenPrograms = new HashSet<>();
    private int lastSerial;
    private int activeSerial;
    private int vertexRewriteSerial;
    private int fragmentRewriteSerial;
    private boolean latched;

    private volatile boolean vertexRewriteLive;

    public static boolean isPuddleUnit(String fileName) {
        return fileName != null
                && (fileName.endsWith(VERTEX_UNIT) || fileName.endsWith(FRAGMENT_UNIT));
    }

    /** Opens a compile and returns its serial, which is never zero. */
    public synchronized int beginCompile() {
        lastSerial++;
        if (lastSerial == 0) {
            lastSerial = 1;
        }
        activeSerial = lastSerial;
        return lastSerial;
    }

    /**
     * Returns the rewritten text of a puddle unit, or {@code source} itself when nothing applies.
     */
    public synchronized String rewrite(String fileName, String source, boolean requirementsMet) {
        if (source == null || latched || !requirementsMet || activeSerial == 0) {
            return source;
        }
        if (fileName == null) {
            return source;
        }
        if (fileName.endsWith(VERTEX_UNIT)) {
            String rewritten = PuddleShaderRewrite.rewriteVertex(source);
            if (rewritten != source) {
                vertexRewriteSerial = activeSerial;
            }
            return rewritten;
        }
        if (fileName.endsWith(FRAGMENT_UNIT)) {
            if (vertexRewriteSerial != activeSerial) {
                return source;
            }
            String rewritten = PuddleShaderRewrite.rewriteFragment(source);
            if (rewritten != source) {
                fragmentRewriteSerial = activeSerial;
            }
            return rewritten;
        }
        return source;
    }

    /**
     * Closes the compile opened with {@code serial}. Returns true when the program is a puddle
     * program, a rewrite was applied during this compile and the compile failed. The rewrite is
     * then latched off, and the caller must compile the program again to get the vanilla shader.
     */
    public synchronized boolean endCompile(int serial, String programName, boolean compiled) {
        boolean rewrote =
                serial != 0 && (vertexRewriteSerial == serial || fragmentRewriteSerial == serial);
        boolean vertexRewritten = serial != 0 && vertexRewriteSerial == serial;
        if (activeSerial == serial) {
            activeSerial = 0;
        }
        if (programName == null || !programName.startsWith(PROGRAM_PREFIX)) {
            return false;
        }
        if (compiled && vertexRewritten) {
            rewrittenPrograms.add(programName);
        } else {
            rewrittenPrograms.remove(programName);
        }
        vertexRewriteLive = !rewrittenPrograms.isEmpty();
        if (rewrote && !compiled) {
            latched = true;
            return true;
        }
        return false;
    }

    /** Latches the rewrite off without asking for a recompile. */
    public synchronized void latch() {
        latched = true;
    }

    /** True while any compiled puddle program carries the vertex rewrite. */
    public boolean isVertexRewriteLive() {
        return vertexRewriteLive;
    }

    public synchronized boolean isLatched() {
        return latched;
    }
}
