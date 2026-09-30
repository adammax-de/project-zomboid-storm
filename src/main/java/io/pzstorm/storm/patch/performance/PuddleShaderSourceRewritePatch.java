package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}. Sub-flag
 * {@code -Dstorm.clientperf.puddles.earlyz=false} turns it off.
 *
 * <p>The puddle fragment shader writes {@code gl_FragDepth}, so the GPU runs the whole water shader
 * for every puddle fragment before it depth-tests, even under walls and objects. Exit advice on
 * {@code ShaderUnit.loadShaderFile(String, ArrayList)} rewrites the two shared puddle units in
 * memory through {@link io.pzstorm.storm.advice.puddleearlyz.PuddleEarlyZ}: the vertex unit sets
 * the clip-space z from the depth attribute, and the fragment unit loses its three depth writes.
 * The depth values stay the same, and the GPU can reject hidden fragments before it shades them. No
 * game file is touched and no shader text ships with Storm. {@link
 * PuddleShaderCompileFallbackPatch} and {@link PuddleDepthClampPatch} complete the change.
 *
 * <p>Why a client bytecode patch: the shader text is assembled in a private Java method. No Lua or
 * event sees it, and Storm never writes into the game install.
 *
 * <p>Fail-soft: the rewrite applies only when every anchor line matches exactly, the driver
 * supports depth clamp and the OpenGL 2.1 shader path is off. The fragment unit is rewritten only
 * when the vertex unit was rewritten in the same program compile. Any mismatch or {@code Throwable}
 * returns the vanilla text. A missing target method fails the transform at weave time and leaves
 * the class vanilla.
 *
 * <p>Re-validate on each game update: the signature and line trimming of {@code
 * ShaderUnit.loadShaderFile}; the anchor lines of {@code puddles_common.vert.glsl} and {@code
 * puddles_common.frag.glsl}; that only the six {@code puddles_hq/mq/lq} root files include them;
 * and that both puddle projections still use near -1 and far 1 with an identity model-view, so clip
 * w is 1.
 */
public class PuddleShaderSourceRewritePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.core.opengl.ShaderUnit";
    private static final String PKG = "io.pzstorm.storm.advice.puddleearlyz.";

    public PuddleShaderSourceRewritePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        ElementMatcher.Junction<MethodDescription> loadShaderFile =
                ElementMatchers.named("loadShaderFile")
                        .and(ElementMatchers.takesArguments(String.class, ArrayList.class))
                        .and(ElementMatchers.returns(String.class));
        if (target.getDeclaredMethods().filter(loadShaderFile).isEmpty()) {
            throw new IllegalStateException(
                    "PuddleShaderSourceRewritePatch: ShaderUnit no longer declares String"
                            + " loadShaderFile(String, ArrayList) — the puddle shader rewrite has"
                            + " no seam. Re-verify the patch against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(PKG + "PuddleShaderSourceAdvice").resolve(), locator)
                        .on(loadShaderFile));
    }
}
