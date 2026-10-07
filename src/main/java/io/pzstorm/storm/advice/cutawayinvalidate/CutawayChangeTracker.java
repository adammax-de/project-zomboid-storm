package io.pzstorm.storm.advice.cutawayinvalidate;

import java.util.Arrays;

/**
 * Decides which chunk-level invalidations one {@code doCutawayVisitSquares} call has to issue.
 *
 * <p>During the call the vanilla invalidations are recorded instead of executed and every square
 * the call writes has its cutaway flag remembered before the first write. {@link #end} then
 * invalidates the chunk level of every square whose flag differs, plus the chunk level of each
 * N/S/W/E neighbour that lives in another chunk, because a wall bakes its cut shape from those four
 * neighbours. A recorded vanilla invalidation that matches none of these is dropped.
 *
 * <p>The bake also reads state this method does not write, and vanilla refreshes it only through
 * the blanket invalidation. A recorded invalidation is therefore replayed as-is whenever that state
 * may have moved since the chunk was last invalidated here:
 *
 * <ul>
 *   <li>the whole call is replayed when the player's room or level changed on any frame since the
 *       previous call, when a wall, slope or orphan-structure range transition ran since the
 *       previous call, or on the first call;
 *   <li>a chunk's records are replayed when the chunk was not touched by the previous call, or when
 *       a could-see flip reached one of its flagged squares;
 *   <li>an edge record is replayed when a touched chunk next to its target is new to this call.
 * </ul>
 *
 * <p>Single-threaded, and allocation-free once the arrays have grown to the scene's size.
 */
public final class CutawayChangeTracker {

    /** Game accessors behind a seam so the decision logic runs against fakes in tests. */
    public interface World {

        int playerCount();

        int flag(Object square, int playerIndex);

        int level(Object square);

        Object chunkOf(Object square);

        /**
         * {@code direction} is one of {@link #NORTH}, {@link #SOUTH}, {@link #WEST}, {@link #EAST}.
         */
        Object neighbour(Object square, int direction);

        int chunkX(Object chunk);

        int chunkY(Object chunk);

        Object renderLevels(Object chunk, int playerIndex);

        Object chunkOfRenderLevels(Object renderLevels);

        /** Identifies the texture that holds {@code level} within {@code renderLevels}. */
        int textureKey(Object renderLevels, int level);

        void invalidate(Object renderLevels, int level, long dirtyFlags);
    }

    public static final int NORTH = 0;
    public static final int SOUTH = 1;
    public static final int WEST = 2;
    public static final int EAST = 3;

    public static final int OWN = 0;
    public static final int EDGE = 1;

    public static final long DIRTY_CUTAWAYS = 2048L;

    private static final int MAX_PLAYERS = 4;
    private static final int MAX_PENDING = 32;

    private final World world;

    public long calls;
    public long blanketCalls;
    public long blanketViewpoint;
    public long blanketExternalWrite;
    public long blanketCouldSeeOverflow;
    public long invalidationsIssued;
    public long invalidationsSkipped;
    public long changedSquares;

    private int player;
    private boolean blanket;

    private Object[] squares = new Object[512];
    private byte[] before = new byte[512];
    private int[] squareSlots = new int[1024];
    private int squareCount;

    private Object[] recordLevels = new Object[64];
    private int[] recordLevel = new int[64];
    private long[] recordFlags = new long[64];
    private byte[] recordKind = new byte[64];
    private boolean[] recordNewChunk = new boolean[64];
    private int recordCount;

    private Object[] issuedLevels = new Object[64];
    private int[] issuedKey = new int[64];
    private int issuedCount;

    private Object owner;
    private final boolean[] hasPrevious = new boolean[MAX_PLAYERS];
    private final Object[] previousRoom = new Object[MAX_PLAYERS];
    private final int[] previousLevel = new int[MAX_PLAYERS];
    private final long[] seenExternalEpoch = new long[MAX_PLAYERS];
    private final boolean[] viewpointMoved = new boolean[MAX_PLAYERS];

    private final Object[][] previousChunks = new Object[MAX_PLAYERS][32];
    private final int[][] previousChunkX = new int[MAX_PLAYERS][32];
    private final int[][] previousChunkY = new int[MAX_PLAYERS][32];
    private final int[] previousChunkCount = new int[MAX_PLAYERS];

    private final Object[][] pendingChunks = new Object[MAX_PLAYERS][MAX_PENDING];
    private final int[] pendingCount = new int[MAX_PLAYERS];
    private final boolean[] pendingOverflow = new boolean[MAX_PLAYERS];

    public CutawayChangeTracker(World world) {
        this.world = world;
    }

    /**
     * @param owner the {@code FBORenderCutaways} instance; a new one means a new world
     * @param room the room the camera character stands in, or any stable token for "none"
     * @param epoch bumped by the caller on every flag write made outside the call
     */
    public void begin(int playerIndex, Object owner, Object room, int playerLevel, long epoch) {
        calls++;
        clearCall();
        player = playerIndex;
        if (owner != this.owner) {
            forget();
            this.owner = owner;
        }
        blanket = true;
        if (!hasPrevious[playerIndex]) {
            blanketCalls++;
        } else if (viewpointMoved[playerIndex]
                || room != previousRoom[playerIndex]
                || playerLevel != previousLevel[playerIndex]) {
            blanketCalls++;
            blanketViewpoint++;
        } else if (epoch != seenExternalEpoch[playerIndex]) {
            blanketCalls++;
            blanketExternalWrite++;
        } else if (pendingOverflow[playerIndex]) {
            blanketCalls++;
            blanketCouldSeeOverflow++;
        } else {
            blanket = false;
        }
        hasPrevious[playerIndex] = true;
        viewpointMoved[playerIndex] = false;
        previousRoom[playerIndex] = room;
        previousLevel[playerIndex] = playerLevel;
        seenExternalEpoch[playerIndex] = epoch;
    }

    /** Remembers the flag of {@code square} as it stood before this call first wrote it. */
    public void rememberBefore(Object square) {
        if (square == null) {
            return;
        }
        int[] slots = squareSlots;
        int mask = slots.length - 1;
        int h = System.identityHashCode(square);
        int i = (h ^ h >>> 16) & mask;
        while (slots[i] != 0) {
            if (squares[slots[i] - 1] == square) {
                return;
            }
            i = (i + 1) & mask;
        }
        if (squareCount == squares.length) {
            squares = Arrays.copyOf(squares, squareCount * 2);
            before = Arrays.copyOf(before, squareCount * 2);
        }
        squares[squareCount] = square;
        before[squareCount] = (byte) world.flag(square, player);
        squareCount++;
        slots[i] = squareCount;
        if (squareCount * 2 > slots.length) {
            rehashSquares();
        }
    }

    /** Records a vanilla invalidation in place of executing it. */
    public void record(Object renderLevels, int level, long dirtyFlags, int kind) {
        if (recordCount == recordLevels.length) {
            int size = recordCount * 2;
            recordLevels = Arrays.copyOf(recordLevels, size);
            recordLevel = Arrays.copyOf(recordLevel, size);
            recordFlags = Arrays.copyOf(recordFlags, size);
            recordKind = Arrays.copyOf(recordKind, size);
            recordNewChunk = Arrays.copyOf(recordNewChunk, size);
        }
        recordLevels[recordCount] = renderLevels;
        recordLevel[recordCount] = level;
        recordFlags[recordCount] = dirtyFlags;
        recordKind[recordCount] = (byte) kind;
        recordCount++;
    }

    /**
     * Issues the invalidations the call needs and forgets the rest.
     *
     * @param threw the vanilla body exited with an exception, so every record is replayed
     */
    public void end(boolean threw) {
        if (threw) {
            replayAll();
            return;
        }
        int p = player;
        invalidateChangedSquares(p);

        for (int r = 0; r < recordCount; r++) {
            if (recordKind[r] == OWN) {
                recordNewChunk[r] = !touchedByPreviousCall(p, recordLevels[r]);
            }
        }
        for (int r = 0; r < recordCount; r++) {
            Object levels = recordLevels[r];
            boolean replay = blanket;
            if (!replay) {
                if (recordKind[r] == OWN) {
                    replay = recordNewChunk[r] || hasPendingFlip(p, levels);
                } else {
                    replay = hasNewTouchedNeighbour(levels);
                }
            }
            if (replay) {
                issue(levels, recordLevel[r], recordFlags[r]);
            } else if (!isIssued(levels, world.textureKey(levels, recordLevel[r]))) {
                invalidationsSkipped++;
            }
        }

        rememberTouchedChunks(p);
        Arrays.fill(pendingChunks[p], 0, pendingCount[p], null);
        pendingCount[p] = 0;
        pendingOverflow[p] = false;
        clearCall();
    }

    /**
     * Executes every recorded invalidation and drops all cross-call memory, which restores the
     * vanilla outcome for the call in flight. Never throws.
     */
    public void replayAll() {
        for (int r = 0; r < recordCount; r++) {
            try {
                world.invalidate(recordLevels[r], recordLevel[r], recordFlags[r]);
            } catch (Throwable ignored) {
                // the remaining records still have to be replayed
            }
        }
        clearCall();
        forget();
    }

    /**
     * Sampled every frame, because the room or level can leave and come back between two calls and
     * a texture baked in between holds the other viewpoint.
     */
    public void noteViewpoint(int playerIndex, Object room, int playerLevel) {
        if (hasPrevious[playerIndex]
                && (room != previousRoom[playerIndex]
                        || playerLevel != previousLevel[playerIndex])) {
            viewpointMoved[playerIndex] = true;
        }
    }

    /**
     * A wall with a door or window bakes a different cut shape depending on whether its own square
     * or the square behind the wall could be seen, and nothing invalidates the texture when that
     * flips. The flagged square's chunk is replayed on the next call instead.
     */
    public void noteCouldSeeFlip(Object square) {
        if (square == null) {
            return;
        }
        int players = Math.min(world.playerCount(), MAX_PLAYERS);
        Object south = null;
        Object east = null;
        boolean neighboursResolved = false;
        for (int p = 0; p < players; p++) {
            if (!hasPrevious[p] || pendingOverflow[p]) {
                continue;
            }
            if (world.flag(square, p) != 0) {
                addPending(p, world.chunkOf(square));
            }
            if (!neighboursResolved) {
                south = world.neighbour(square, SOUTH);
                east = world.neighbour(square, EAST);
                neighboursResolved = true;
            }
            if (south != null && (world.flag(south, p) & 1) != 0) {
                addPending(p, world.chunkOf(south));
            }
            if (east != null && (world.flag(east, p) & 2) != 0) {
                addPending(p, world.chunkOf(east));
            }
        }
    }

    /** Drops all cross-call memory, so each player's next call replays everything. */
    public void forget() {
        owner = null;
        for (int p = 0; p < MAX_PLAYERS; p++) {
            hasPrevious[p] = false;
            viewpointMoved[p] = false;
            previousRoom[p] = null;
            Arrays.fill(previousChunks[p], 0, previousChunkCount[p], null);
            previousChunkCount[p] = 0;
            Arrays.fill(pendingChunks[p], 0, pendingCount[p], null);
            pendingCount[p] = 0;
            pendingOverflow[p] = false;
        }
    }

    private void invalidateChangedSquares(int p) {
        for (int i = 0; i < squareCount; i++) {
            Object square = squares[i];
            if (world.flag(square, p) == before[i]) {
                continue;
            }
            changedSquares++;
            Object chunk = world.chunkOf(square);
            if (chunk == null) {
                continue;
            }
            int level = world.level(square);
            issue(world.renderLevels(chunk, p), level, DIRTY_CUTAWAYS);
            for (int direction = NORTH; direction <= EAST; direction++) {
                Object adjacent = world.neighbour(square, direction);
                if (adjacent == null) {
                    continue;
                }
                Object adjacentChunk = world.chunkOf(adjacent);
                if (adjacentChunk != null && adjacentChunk != chunk) {
                    issue(world.renderLevels(adjacentChunk, p), level, DIRTY_CUTAWAYS);
                }
            }
        }
    }

    private void issue(Object renderLevels, int level, long dirtyFlags) {
        int key = world.textureKey(renderLevels, level);
        if (isIssued(renderLevels, key)) {
            return;
        }
        world.invalidate(renderLevels, level, dirtyFlags);
        if (issuedCount == issuedLevels.length) {
            issuedLevels = Arrays.copyOf(issuedLevels, issuedCount * 2);
            issuedKey = Arrays.copyOf(issuedKey, issuedCount * 2);
        }
        issuedLevels[issuedCount] = renderLevels;
        issuedKey[issuedCount] = key;
        issuedCount++;
        invalidationsIssued++;
    }

    private boolean isIssued(Object renderLevels, int key) {
        for (int i = 0; i < issuedCount; i++) {
            if (issuedLevels[i] == renderLevels && issuedKey[i] == key) {
                return true;
            }
        }
        return false;
    }

    private boolean touchedByPreviousCall(int p, Object renderLevels) {
        Object chunk = world.chunkOfRenderLevels(renderLevels);
        Object[] chunks = previousChunks[p];
        for (int i = 0; i < previousChunkCount[p]; i++) {
            // pooled chunk objects come back at other coordinates
            if (chunks[i] == chunk
                    && previousChunkX[p][i] == world.chunkX(chunk)
                    && previousChunkY[p][i] == world.chunkY(chunk)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPendingFlip(int p, Object renderLevels) {
        if (pendingCount[p] == 0) {
            return false;
        }
        Object chunk = world.chunkOfRenderLevels(renderLevels);
        Object[] pending = pendingChunks[p];
        for (int i = 0; i < pendingCount[p]; i++) {
            if (pending[i] == chunk) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vanilla's edge booleans are only as fresh as the last call that touched their chunk, so an
     * edge invalidation raised by a chunk this filter did not see last call is kept.
     */
    private boolean hasNewTouchedNeighbour(Object targetLevels) {
        Object target = world.chunkOfRenderLevels(targetLevels);
        int x = world.chunkX(target);
        int y = world.chunkY(target);
        for (int r = 0; r < recordCount; r++) {
            if (recordKind[r] != OWN || !recordNewChunk[r]) {
                continue;
            }
            Object source = world.chunkOfRenderLevels(recordLevels[r]);
            if (Math.abs(world.chunkX(source) - x) + Math.abs(world.chunkY(source) - y) == 1) {
                return true;
            }
        }
        return false;
    }

    private void rememberTouchedChunks(int p) {
        Arrays.fill(previousChunks[p], 0, previousChunkCount[p], null);
        int count = 0;
        for (int r = 0; r < recordCount; r++) {
            if (recordKind[r] != OWN) {
                continue;
            }
            if (count == previousChunks[p].length) {
                previousChunks[p] = Arrays.copyOf(previousChunks[p], count * 2);
                previousChunkX[p] = Arrays.copyOf(previousChunkX[p], count * 2);
                previousChunkY[p] = Arrays.copyOf(previousChunkY[p], count * 2);
            }
            Object chunk = world.chunkOfRenderLevels(recordLevels[r]);
            previousChunks[p][count] = chunk;
            previousChunkX[p][count] = world.chunkX(chunk);
            previousChunkY[p][count] = world.chunkY(chunk);
            count++;
        }
        previousChunkCount[p] = count;
    }

    private void addPending(int p, Object chunk) {
        if (chunk == null) {
            return;
        }
        Object[] pending = pendingChunks[p];
        int count = pendingCount[p];
        for (int i = count - 1; i >= 0; i--) {
            if (pending[i] == chunk) {
                return;
            }
        }
        if (count == MAX_PENDING) {
            pendingOverflow[p] = true;
            return;
        }
        pending[count] = chunk;
        pendingCount[p] = count + 1;
    }

    private void clearCall() {
        if (squareCount != 0) {
            Arrays.fill(squares, 0, squareCount, null);
            Arrays.fill(squareSlots, 0);
            squareCount = 0;
        }
        Arrays.fill(recordLevels, 0, recordCount, null);
        recordCount = 0;
        Arrays.fill(issuedLevels, 0, issuedCount, null);
        issuedCount = 0;
    }

    private void rehashSquares() {
        int[] slots = new int[squareSlots.length * 2];
        int mask = slots.length - 1;
        for (int n = 0; n < squareCount; n++) {
            int h = System.identityHashCode(squares[n]);
            int i = (h ^ h >>> 16) & mask;
            while (slots[i] != 0) {
                i = (i + 1) & mask;
            }
            slots[i] = n + 1;
        }
        squareSlots = slots;
    }
}
