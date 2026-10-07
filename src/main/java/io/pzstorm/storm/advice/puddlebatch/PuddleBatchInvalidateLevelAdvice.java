package io.pzstorm.storm.advice.puddlebatch;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.iso.fboRenderChunk.FBORenderLevels.invalidateLevel(int, long)}. Tells
 * the puddle batch cache that the level was invalidated, which covers every vertex-light change.
 * The vanilla body always runs.
 */
public class PuddleBatchInvalidateLevelAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object self, @Advice.Argument(0) int level) {
        PuddleBatchHooks.onInvalidateLevel(self, level);
    }
}
