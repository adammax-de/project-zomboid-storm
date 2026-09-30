package io.pzstorm.storm.advice.puddlebatch;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.iso.IsoChunk.resetForStore()}. Tells the puddle batch cache that the
 * chunk object is going back to the pool. The vanilla body always runs.
 */
public class PuddleBatchChunkResetAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object self) {
        PuddleBatchHooks.onChunkReset(self);
    }
}
