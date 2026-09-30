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
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}.
 *
 * <p>Invalidation hooks for the puddle batch cache of {@link PuddleBatchRenderPatch}. {@code
 * FBORenderLevels.clearCachedSquares(int)} runs before a chunk level group is re-rendered and its
 * puddle list refilled, so enter advice there marks the batches of the group for a rebuild. {@code
 * FBORenderLevels.invalidateLevel(int, long)} runs on every vertex-light change, so enter advice
 * there marks the batch of the level for a light re-read. Both vanilla bodies always run.
 *
 * <p>Why a client bytecode patch: these are Java methods with no Lua event, and the cache is wrong
 * without them.
 *
 * <p>Fail-soft: each hook does one table lookup and one volatile flag write, returns at once while
 * the cache is empty, and is safe off the game thread. A {@code Throwable} in a hook latches the
 * batch path off and logs once, and {@code suppress = Throwable.class} keeps any advice failure out
 * of the vanilla method. A missing target method fails the transform at weave time and leaves the
 * class vanilla.
 *
 * <p>Re-validate on each game update: {@code clearCachedSquares(int)} and {@code
 * invalidateLevel(int, long)} still exist with the same meaning; the clear and fill sites of {@code
 * NLevels.puddleSquares}; {@code calculateMinLevel} and the two-level grouping; and that a
 * vertex-light change in {@code LightingJNI} still writes the light before it calls {@code
 * invalidateLevel}.
 */
public class PuddleBatchInvalidationPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.fboRenderChunk.FBORenderLevels";
    private static final String PKG = "io.pzstorm.storm.advice.puddlebatch.";

    public PuddleBatchInvalidationPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        ElementMatcher.Junction<MethodDescription> clearCachedSquares =
                ElementMatchers.named("clearCachedSquares")
                        .and(ElementMatchers.takesArguments(int.class))
                        .and(ElementMatchers.returns(void.class));
        ElementMatcher.Junction<MethodDescription> invalidateLevel =
                ElementMatchers.named("invalidateLevel")
                        .and(ElementMatchers.takesArguments(int.class, long.class))
                        .and(ElementMatchers.returns(void.class));
        require(target, clearCachedSquares, "clearCachedSquares(int)");
        require(target, invalidateLevel, "invalidateLevel(int, long)");
        return builder.visit(
                        advice(typePool, locator, "PuddleBatchClearCachedSquaresAdvice")
                                .on(clearCachedSquares))
                .visit(
                        advice(typePool, locator, "PuddleBatchInvalidateLevelAdvice")
                                .on(invalidateLevel));
    }

    private static Advice advice(TypePool typePool, ClassFileLocator locator, String name) {
        return Advice.to(typePool.describe(PKG + name).resolve(), locator);
    }

    private static void require(
            TypeDescription target,
            ElementMatcher.Junction<MethodDescription> matcher,
            String signature) {
        if (target.getDeclaredMethods().filter(matcher).isEmpty()) {
            throw new IllegalStateException(
                    "PuddleBatchInvalidationPatch: FBORenderLevels no longer declares "
                            + signature
                            + " — the puddle batch cache would miss invalidations. Re-verify"
                            + " the patch against the current game source.");
        }
    }
}
