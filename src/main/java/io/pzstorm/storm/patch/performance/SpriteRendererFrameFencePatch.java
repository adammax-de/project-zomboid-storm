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
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}. Companion of
 * {@link GLVertexBufferObjectPersistentMapPatch}: exit advice on {@code
 * SpriteRenderer.postRender()} — the render-thread method every draw of a frame is issued from —
 * places one {@code glFenceSync} per frame. That fence is what a persistently mapped slot waits on
 * before it is rewritten. Without this patch no buffer is ever adopted, because adoption waits for
 * the frame counter to move.
 *
 * <p>Why a client bytecode patch: the draw boundary is a private-side effect of a Java method on
 * the render thread; nothing in Lua or on the server marks it. Fail-soft: the advice is {@code
 * suppress = Throwable.class} and the helper never throws; a sync failure latches the feature off
 * for new buffers and existing ones fall back to {@code glFinish}. A missing {@code postRender()}
 * fails the transform at weave time and leaves the class vanilla. Re-validate on each game update
 * that {@code postRender()} still issues all of a frame's draws.
 */
public class SpriteRendererFrameFencePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.core.SpriteRenderer";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.persistentvbo.SpriteRendererPostRenderAdvice";

    public SpriteRendererFrameFencePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        ElementMatcher.Junction<MethodDescription> postRender =
                ElementMatchers.named("postRender")
                        .and(ElementMatchers.takesNoArguments())
                        .and(ElementMatchers.returns(void.class))
                        .and(ElementMatchers.not(ElementMatchers.isStatic()));
        if (target.getDeclaredMethods().filter(postRender).isEmpty()) {
            throw new IllegalStateException(
                    "SpriteRendererFrameFencePatch: SpriteRenderer no longer declares an instance"
                            + " postRender() — persistent VBO slots would never be fenced."
                            + " Re-verify the patch against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator).on(postRender));
    }
}
