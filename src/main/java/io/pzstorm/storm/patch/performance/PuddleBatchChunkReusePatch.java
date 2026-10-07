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
 * <p>The puddle batch cache of {@link PuddleBatchRenderPatch} is keyed by chunk object, and the
 * game pools chunk objects and reuses them for other positions. Enter advice on {@code
 * IsoChunk.resetForStore()} marks every batch of the chunk for a rebuild, so a reused chunk never
 * draws the puddles of its previous position. The batches keep their GL buffers and upload again
 * after the rebuild. The vanilla body always runs.
 *
 * <p>Why a client bytecode patch: chunk pooling is Java-only and raises no Lua event.
 *
 * <p>Fail-soft: the hook does one table lookup and volatile flag writes, and returns at once while
 * the cache is empty. A {@code Throwable} in the hook latches the batch path off and logs once, and
 * {@code suppress = Throwable.class} keeps any advice failure out of the vanilla method. A missing
 * target method fails the transform at weave time and leaves the class vanilla.
 *
 * <p>Re-validate on each game update: {@code IsoChunk.resetForStore()} still exists and still runs
 * whenever a pooled chunk object is reused for another position.
 */
public class PuddleBatchChunkReusePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.IsoChunk";
    private static final String PKG = "io.pzstorm.storm.advice.puddlebatch.";

    public PuddleBatchChunkReusePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        ElementMatcher.Junction<MethodDescription> resetForStore =
                ElementMatchers.named("resetForStore")
                        .and(ElementMatchers.takesNoArguments())
                        .and(ElementMatchers.returns(void.class));
        if (target.getDeclaredMethods().filter(resetForStore).isEmpty()) {
            throw new IllegalStateException(
                    "PuddleBatchChunkReusePatch: IsoChunk no longer declares resetForStore()"
                            + " — a reused chunk would keep its old puddle batches. Re-verify"
                            + " the patch against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(PKG + "PuddleBatchChunkResetAdvice").resolve(), locator)
                        .on(resetForStore));
    }
}
