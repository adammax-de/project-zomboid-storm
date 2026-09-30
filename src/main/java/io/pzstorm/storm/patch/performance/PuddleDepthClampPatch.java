package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
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
 * <p>Depth clamp for the vanilla puddle draw while the shader rewrite of {@link
 * PuddleShaderSourceRewritePatch} is live. The vanilla shader writes {@code gl_FragDepth}, which
 * the GPU clamps to the depth range. The rewritten shader sends the same depth through {@code
 * gl_Position.z}, and a z outside the clip volume is clipped. Puddle depth is relative to the
 * camera chunk and goes below zero for nearer chunks, so enter and exit advice on {@code
 * IsoPuddles.renderSome(int, int, boolean)} enables {@code GL_DEPTH_CLAMP} for the draw and
 * disables it after, also when the draw throws. The batch draw of {@link PuddleBatchRenderPatch}
 * does the same on its own.
 *
 * <p>Why a client bytecode patch: this is GL state around a private Java draw on the render thread.
 * No Lua or event reaches it.
 *
 * <p>Fail-soft: the advice does nothing while no rewritten puddle program is live, which includes
 * missing driver support, the kill switch and the compile fallback. A missing target method fails
 * the transform at weave time and leaves the class vanilla. The source rewrite then still applies,
 * so this patch and {@link PuddleShaderSourceRewritePatch} must be validated together.
 *
 * <p>Re-validate on each game update: {@code renderSome(int, int, boolean)} is still the one draw
 * call of both vanilla puddle paths (screen and chunk texture); it still draws with depth mask off
 * and {@code GL_LEQUAL}; {@code glDepthRange} is still (0, 1) on puddle paths; and vanilla still
 * does not use {@code GL_DEPTH_CLAMP} itself.
 */
public class PuddleDepthClampPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.IsoPuddles";
    private static final String PKG = "io.pzstorm.storm.advice.puddleearlyz.";

    public PuddleDepthClampPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        ElementMatcher.Junction<MethodDescription> renderSome =
                ElementMatchers.named("renderSome")
                        .and(ElementMatchers.takesArguments(int.class, int.class, boolean.class))
                        .and(ElementMatchers.returns(int.class));
        if (target.getDeclaredMethods().filter(renderSome).isEmpty()) {
            throw new IllegalStateException(
                    "PuddleDepthClampPatch: IsoPuddles no longer declares int renderSome(int,"
                            + " int, boolean) — rewritten puddle shaders would draw without"
                            + " depth clamp. Re-verify the patch against the current game"
                            + " source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(PKG + "PuddleDepthClampAdvice").resolve(), locator)
                        .on(renderSome));
    }
}
