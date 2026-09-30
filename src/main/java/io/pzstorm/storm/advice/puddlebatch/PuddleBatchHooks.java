package io.pzstorm.storm.advice.puddlebatch;

import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Invalidation hooks of the puddle batch cache, called from advice on {@code FBORenderLevels} and
 * {@code IsoChunk}. Each hook does one table lookup and one flag write, and returns at once while
 * the table is empty. Lighting runs on other threads, so nothing here touches game-thread state.
 */
public final class PuddleBatchHooks {

    private PuddleBatchHooks() {}

    /**
     * The puddle list of a two-level group is about to be cleared for a re-render, so both batches
     * of the group must be rebuilt from the refilled list.
     */
    public static void onClearCachedSquares(Object renderLevels, int level) {
        if (!PuddleBatchRenderer.isActive()) {
            return;
        }
        try {
            FBORenderLevels levels = (FBORenderLevels) renderLevels;
            Object chunk = levels.getChunk();
            int playerIndex = levels.getPlayerIndex();
            int minLevel = FBORenderLevels.calculateMinLevel(level);
            PuddleBatchRenderer.TABLE.markInvalid(chunk, playerIndex, minLevel);
            PuddleBatchRenderer.TABLE.markInvalid(chunk, playerIndex, minLevel + 1);
        } catch (Throwable t) {
            PuddleBatchRenderer.fail("clearCachedSquares hook", t);
        }
    }

    /** Every vertex-light change invalidates its level, so the batch re-reads its lights. */
    public static void onInvalidateLevel(Object renderLevels, int level) {
        if (!PuddleBatchRenderer.isActive()) {
            return;
        }
        try {
            FBORenderLevels levels = (FBORenderLevels) renderLevels;
            PuddleBatchRenderer.TABLE.markLightsDirty(
                    levels.getChunk(), levels.getPlayerIndex(), level);
        } catch (Throwable t) {
            PuddleBatchRenderer.fail("invalidateLevel hook", t);
        }
    }

    /** A pooled chunk object is being reused, so its batches describe squares that are gone. */
    public static void onChunkReset(Object chunk) {
        if (!PuddleBatchRenderer.isActive()) {
            return;
        }
        try {
            PuddleBatchRenderer.TABLE.markChunkInvalid(chunk);
        } catch (Throwable t) {
            PuddleBatchRenderer.fail("resetForStore hook", t);
        }
    }
}
