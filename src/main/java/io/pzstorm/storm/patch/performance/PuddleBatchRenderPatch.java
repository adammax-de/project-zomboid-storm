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
 * {@code -Dstorm.clientperf.puddles.cache=false} turns it off.
 *
 * <p>At high and medium puddle quality {@code FBORenderCell.renderPuddles(int)} filters, lights and
 * packs 32 floats for every puddle square of every on-screen chunk level on every frame, and the
 * render thread copies them all into a shared buffer. This patch routes the method through {@link
 * io.pzstorm.storm.advice.puddlebatch.PuddleBatchRenderer}, which keeps the packed vertices of each
 * chunk level in a GL buffer of its own and repacks or re-uploads a level only when its squares,
 * cutaway flags or lights changed, the camera crossed a chunk edge, or a backstop interval ({@code
 * -Dstorm.clientperf.puddles.cacheFrames}, default 60) ran out. The vanilla body stays in place as
 * the fallback. {@link PuddleBatchInvalidationPatch} and {@link PuddleBatchChunkReusePatch} supply
 * the invalidation hooks.
 *
 * <p>Why a client bytecode patch: the cost is a private Java render loop and the GL upload behind
 * it. No Lua, event or server change reaches them.
 *
 * <p>Fail-soft: any {@code Throwable} in the batch pass submits nothing, logs once and latches the
 * path off, and the vanilla body runs for that frame and every later one. A {@code Throwable} in
 * the render-thread draw restores the GL state and latches the same way. A missing target method
 * fails the transform at weave time and leaves the class vanilla.
 *
 * <p>Re-validate on each game update: the signature, gates, loop order and square filter of {@code
 * renderPuddles} and its call-site gate; the gates of {@code IsoPuddles.render}; the {@code
 * IsoPuddles.RenderData.addSquare} layout (8 floats per vertex, their order, the jiggle and the
 * depth bias); the GL state, attrib layout, index pattern and restore steps of {@code
 * ModelManager.RenderPuddles} and {@code IsoPuddles.renderSome}; {@code
 * IsoDepthHelper.getChunkDepthData} and the chunk-constant property of the depth; the vertex order
 * 0, 3, 2, 1 of {@code IsoPuddlesGeometry.updateLighting}; the layout and bit 0 of {@code
 * ChunkLevelData.squareFlags}; and the {@code SpriteRenderer.drawGeneric} and {@code
 * TextureDraw.GenericDrawer} contract.
 */
public class PuddleBatchRenderPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.fboRenderChunk.FBORenderCell";
    private static final String PKG = "io.pzstorm.storm.advice.puddlebatch.";

    public PuddleBatchRenderPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        ElementMatcher.Junction<MethodDescription> renderPuddles =
                ElementMatchers.named("renderPuddles")
                        .and(ElementMatchers.takesArguments(int.class))
                        .and(ElementMatchers.returns(void.class));
        if (target.getDeclaredMethods().filter(renderPuddles).isEmpty()) {
            throw new IllegalStateException(
                    "PuddleBatchRenderPatch: FBORenderCell no longer declares"
                            + " renderPuddles(int) — the puddle batch pass has nothing to"
                            + " replace. Re-verify the patch against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(PKG + "PuddleBatchRenderAdvice").resolve(), locator)
                        .on(renderPuddles));
    }
}
