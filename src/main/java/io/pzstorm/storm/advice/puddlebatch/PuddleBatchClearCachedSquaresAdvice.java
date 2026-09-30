package io.pzstorm.storm.advice.puddlebatch;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.iso.fboRenderChunk.FBORenderLevels.clearCachedSquares(int)}. Tells the
 * puddle batch cache that the puddle list of the level group is being cleared. The vanilla body
 * always runs.
 */
public class PuddleBatchClearCachedSquaresAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object self, @Advice.Argument(0) int level) {
        PuddleBatchHooks.onClearCachedSquares(self, level);
    }
}
