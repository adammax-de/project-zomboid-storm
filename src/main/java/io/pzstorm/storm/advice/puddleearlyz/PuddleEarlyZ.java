package io.pzstorm.storm.advice.puddleearlyz;

import io.pzstorm.storm.logging.StormLogger;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import zombie.core.Core;
import zombie.core.opengl.ShaderProgram;

/**
 * Early depth test for puddles. The vanilla puddle fragment shader writes {@code gl_FragDepth},
 * which makes the GPU run the whole water shader for every fragment before it depth-tests. This
 * class rewrites the two shared puddle shader units in memory while they load, so the vertex shader
 * supplies the same depth through {@code gl_Position.z} and the fragment shader no longer writes
 * it. No game file is touched and no shader text ships with Storm.
 *
 * <p>{@code gl_FragDepth} is clamped to the depth range, but a clip-space z outside the clip volume
 * is clipped. Puddle depth goes below zero for chunks nearer than the camera chunk, so every puddle
 * draw runs with {@code GL_DEPTH_CLAMP} while a rewritten program is live. The rewrite therefore
 * needs depth clamp support and the core-profile shader path.
 *
 * <p>Fail soft: an anchor mismatch or any {@code Throwable} returns the vanilla text. A rewritten
 * puddle program that fails to compile latches the rewrite off, logs once and compiles again as
 * vanilla.
 *
 * <p>Shader compiles and puddle draws both run on the render thread.
 */
public final class PuddleEarlyZ {

    private static final int GL_DEPTH_CLAMP = 34383;

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("storm.clientperf.puddles.earlyz", "true"));

    private static final PuddleEarlyZState STATE = new PuddleEarlyZState();

    private static boolean requirementsChecked;
    private static boolean requirementsMet;
    private static boolean sourceFailureLogged;
    private static boolean rewriteLogged;

    private PuddleEarlyZ() {}

    /** Exit hook of {@code ShaderUnit.loadShaderFile}. Returns the text the unit compiles. */
    public static String onShaderSource(String fileName, String source) {
        if (!ENABLED || !PuddleEarlyZState.isPuddleUnit(fileName)) {
            return source;
        }
        try {
            String rewritten = STATE.rewrite(fileName, source, requirementsMet());
            if (rewritten != source && !rewriteLogged) {
                rewriteLogged = true;
                StormLogger.LOGGER.info(
                        "[PuddleEarlyZ] puddle shaders rewritten for early depth testing");
            }
            return rewritten;
        } catch (Throwable t) {
            STATE.latch();
            if (!sourceFailureLogged) {
                sourceFailureLogged = true;
                StormLogger.LOGGER.error(
                        "[PuddleEarlyZ] shader rewrite failed; vanilla puddle shaders are used", t);
            }
            return source;
        }
    }

    /** Enter hook of {@code ShaderProgram.compile()}. Returns the serial of this compile. */
    public static int onCompileEnter() {
        if (!ENABLED) {
            return 0;
        }
        return STATE.beginCompile();
    }

    /**
     * Exit hook of {@code ShaderProgram.compile()}. When a rewritten puddle program failed to
     * compile, the rewrite is latched off and the program compiles once more as vanilla. A compile
     * that threw is left to the game, which compiles again on the next use of the shader.
     */
    public static void onCompileExit(Object program, int serial, Throwable thrown) {
        if (serial == 0) {
            return;
        }
        try {
            ShaderProgram shaderProgram = (ShaderProgram) program;
            String name = shaderProgram.getName();
            boolean compiled = thrown == null && shaderProgram.isCompiled();
            if (!STATE.endCompile(serial, name, compiled)) {
                return;
            }
            StormLogger.LOGGER.error(
                    "[PuddleEarlyZ] rewritten puddle shader {} failed to compile; early depth"
                            + " testing is off and the vanilla shader is used",
                    name);
            if (thrown == null) {
                shaderProgram.compile();
            }
        } catch (Throwable t) {
            STATE.latch();
            StormLogger.LOGGER.error("[PuddleEarlyZ] compile fallback failed", t);
        }
    }

    /**
     * Turns depth clamp on for a puddle draw when a rewritten program is live. Returns true when
     * the caller must call {@link #endClamp()} after the draw.
     */
    public static boolean beginClamp() {
        if (!STATE.isVertexRewriteLive()) {
            return false;
        }
        GL11.glEnable(GL_DEPTH_CLAMP);
        return true;
    }

    public static void endClamp() {
        GL11.glDisable(GL_DEPTH_CLAMP);
    }

    public static boolean isLive() {
        return STATE.isVertexRewriteLive();
    }

    public static boolean isLatched() {
        return STATE.isLatched();
    }

    private static boolean requirementsMet() {
        if (!requirementsChecked) {
            requirementsMet = checkRequirements();
            requirementsChecked = true;
        }
        return requirementsMet;
    }

    private static boolean checkRequirements() {
        try {
            GLCapabilities caps = GL.getCapabilities();
            boolean depthClamp = caps.OpenGL32 || caps.GL_ARB_depth_clamp;
            boolean legacyShaders = Core.getInstance().getUseOpenGL21();
            if (!depthClamp || legacyShaders) {
                StormLogger.LOGGER.info(
                        "[PuddleEarlyZ] unavailable (depth_clamp={}, OpenGL 2.1 shaders={});"
                                + " using vanilla puddle shaders",
                        depthClamp,
                        legacyShaders);
                return false;
            }
            return true;
        } catch (Throwable t) {
            StormLogger.LOGGER.warn(
                    "[PuddleEarlyZ] capability check failed; using vanilla puddle shaders", t);
            return false;
        }
    }
}
