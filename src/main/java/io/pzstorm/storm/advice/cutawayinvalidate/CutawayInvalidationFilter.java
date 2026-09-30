package io.pzstorm.storm.advice.cutawayinvalidate;

import io.pzstorm.storm.logging.StormLogger;
import zombie.characters.IsoPlayer;
import zombie.core.PerformanceSettings;
import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Call-site hooks woven into {@code FBORenderCutaways} by {@code CutawayChangedInvalidationPatch}
 * and {@code CutawayFlagClearTagPatch}. They feed {@link CutawayChangeTracker}, which holds the
 * mechanism and the parity argument.
 *
 * <p>Square flag writes always run the vanilla call. Invalidations made inside {@code
 * doCutawayVisitSquares} are recorded and settled at method exit. Any {@link Throwable} from Storm
 * code logs once, replays every recorded invalidation of the call in flight and latches the hooks
 * into plain pass-through, which is the vanilla behaviour.
 */
public final class CutawayInvalidationFilter {

    /** Runtime switch for A/B checks through the Java eval endpoint. */
    public static volatile boolean enabled = true;

    public static final String TAGGED_CLASS =
            "zombie.iso.fboRenderChunk.FBORenderCutaways$ChunkLevelsData";
    public static final String TAG_FIELD = "storm$cutawayWriteTagged";

    private static final Object NO_SQUARE = new Object();

    /**
     * Created on first use inside the guarded path, so a game class that no longer links latches
     * the filter off instead of failing this class's initialiser under a woven call site.
     */
    private static CutawayChangeTracker tracker;

    private static volatile boolean failed;
    private static volatile long externalEpoch;
    private static boolean recording;
    private static boolean dormant;
    private static Thread renderThread;

    private CutawayInvalidationFilter() {}

    public static void begin(Object owner, int playerIndex) {
        if (failed) {
            return;
        }
        try {
            if (tracker == null) {
                requireClearWritersTagged(owner);
                tracker = new CutawayChangeTracker(new GameWorld());
            }
            if (recording) {
                recording = false;
                tracker.replayAll();
            }
            if (!enabled || !PerformanceSettings.fboRenderChunk) {
                if (!dormant) {
                    dormant = true;
                    tracker.forget();
                }
                return;
            }
            dormant = false;
            renderThread = Thread.currentThread();
            tracker.begin(playerIndex, owner, currentRoom(), currentLevel(), externalEpoch);
            recording = true;
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void end(boolean threw) {
        if (!recording) {
            return;
        }
        recording = false;
        try {
            tracker.end(threw);
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void setFlag(IsoGridSquare square, int playerIndex, int flags, long now) {
        if (recording) {
            try {
                tracker.rememberBefore(square);
            } catch (Throwable t) {
                fail(t);
            }
        }
        square.setPlayerCutawayFlag(playerIndex, flags, now);
    }

    public static void addFlag(IsoGridSquare square, int playerIndex, int flag, long now) {
        if (recording) {
            try {
                tracker.rememberBefore(square);
            } catch (Throwable t) {
                fail(t);
            }
        }
        square.addPlayerCutawayFlag(playerIndex, flag, now);
    }

    public static void ownInvalidate(FBORenderLevels renderLevels, int level, long dirtyFlags) {
        if (!record(renderLevels, level, dirtyFlags, CutawayChangeTracker.OWN)) {
            renderLevels.invalidateLevel(level, dirtyFlags);
        }
    }

    public static void edgeInvalidate(FBORenderLevels renderLevels, int level, long dirtyFlags) {
        if (!record(renderLevels, level, dirtyFlags, CutawayChangeTracker.EDGE)) {
            renderLevels.invalidateLevel(level, dirtyFlags);
        }
    }

    /**
     * Wraps the invalidations that follow a flag write or range transition made outside {@code
     * doCutawayVisitSquares}. Those writers invalidate only their own chunk and lean on the next
     * blanket pass for the neighbours, so the next call of every player replays everything.
     */
    public static void externalInvalidate(
            FBORenderLevels renderLevels, int level, long dirtyFlags) {
        externalEpoch++;
        renderLevels.invalidateLevel(level, dirtyFlags);
    }

    public static void onSquareChanged(Object square) {
        if (failed || square == null) {
            return;
        }
        if (Thread.currentThread() != renderThread) {
            externalEpoch++;
            return;
        }
        try {
            tracker.noteCouldSeeFlip(square);
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void onViewpointCheck(int playerIndex) {
        if (failed || dormant || tracker == null) {
            return;
        }
        try {
            tracker.noteViewpoint(playerIndex, currentRoom(), currentLevel());
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static long calls() {
        CutawayChangeTracker t = tracker;
        return t == null ? 0L : t.calls;
    }

    public static long blanketCalls() {
        CutawayChangeTracker t = tracker;
        return t == null ? 0L : t.blanketCalls;
    }

    public static long invalidationsIssued() {
        CutawayChangeTracker t = tracker;
        return t == null ? 0L : t.invalidationsIssued;
    }

    public static long invalidationsSkipped() {
        CutawayChangeTracker t = tracker;
        return t == null ? 0L : t.invalidationsSkipped;
    }

    public static long changedSquares() {
        CutawayChangeTracker t = tracker;
        return t == null ? 0L : t.changedSquares;
    }

    public static boolean failed() {
        return failed;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static String stats() {
        CutawayChangeTracker t = tracker;
        if (t == null) {
            return "calls=0 enabled=" + enabled + " failed=" + failed;
        }
        return "calls="
                + t.calls
                + " blanketCalls="
                + t.blanketCalls
                + " (viewpoint="
                + t.blanketViewpoint
                + " externalWrite="
                + t.blanketExternalWrite
                + " couldSeeOverflow="
                + t.blanketCouldSeeOverflow
                + ") invalidationsIssued="
                + t.invalidationsIssued
                + " invalidationsSkipped="
                + t.invalidationsSkipped
                + " changedSquares="
                + t.changedSquares
                + " enabled="
                + enabled
                + " failed="
                + failed;
    }

    private static boolean record(FBORenderLevels renderLevels, int level, long flags, int kind) {
        if (!recording || renderLevels == null) {
            return false;
        }
        try {
            tracker.record(renderLevels, level, flags, kind);
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    private static Object currentRoom() {
        IsoGridSquare square = IsoCamera.frameState.camCharacterSquare;
        return square == null ? NO_SQUARE : square.getRoom();
    }

    private static int currentLevel() {
        return PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
    }

    /**
     * The epoch only covers the flag-clearing writers when the sibling patch has woven them, so
     * filtering without it could keep a stale neighbour texture.
     */
    private static void requireClearWritersTagged(Object owner)
            throws ReflectiveOperationException {
        Class.forName(TAGGED_CLASS, false, owner.getClass().getClassLoader()).getField(TAG_FIELD);
    }

    private static void fail(Throwable t) {
        boolean first = !failed;
        failed = true;
        recording = false;
        if (tracker != null) {
            tracker.replayAll();
        }
        if (first) {
            try {
                StormLogger.LOGGER.error(
                        "Cutaway invalidation filter failed; reverting to vanilla invalidation", t);
            } catch (Throwable ignored) {
                // logging must not break the vanilla path
            }
        }
    }

    private static final class GameWorld implements CutawayChangeTracker.World {

        private static final IsoDirections[] DIRECTIONS = {
            IsoDirections.N, IsoDirections.S, IsoDirections.W, IsoDirections.E
        };

        @Override
        public int playerCount() {
            return IsoPlayer.numPlayers;
        }

        @Override
        public int flag(Object square, int playerIndex) {
            return ((IsoGridSquare) square).getPlayerCutawayFlag(playerIndex, 0L);
        }

        @Override
        public int level(Object square) {
            return ((IsoGridSquare) square).z;
        }

        @Override
        public Object chunkOf(Object square) {
            return ((IsoGridSquare) square).getChunk();
        }

        @Override
        public Object neighbour(Object square, int direction) {
            return ((IsoGridSquare) square).getAdjacentSquare(DIRECTIONS[direction]);
        }

        @Override
        public int chunkX(Object chunk) {
            return ((IsoChunk) chunk).wx;
        }

        @Override
        public int chunkY(Object chunk) {
            return ((IsoChunk) chunk).wy;
        }

        @Override
        public Object renderLevels(Object chunk, int playerIndex) {
            return ((IsoChunk) chunk).getRenderLevels(playerIndex);
        }

        @Override
        public Object chunkOfRenderLevels(Object renderLevels) {
            return ((FBORenderLevels) renderLevels).getChunk();
        }

        @Override
        public int textureKey(Object renderLevels, int level) {
            return FBORenderLevels.calculateMinLevel(level);
        }

        @Override
        public void invalidate(Object renderLevels, int level, long dirtyFlags) {
            ((FBORenderLevels) renderLevels).invalidateLevel(level, dirtyFlags);
        }
    }
}
