package io.pzstorm.storm.advice.cutawayinvalidate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link CutawayChangeTracker} with a fake world through the same sequence of writes and
 * invalidations the vanilla {@code doCutawayVisitSquares} body performs.
 */
class CutawayChangeTrackerTest implements UnitTest {

    private static final Object OWNER = new Object();
    private static final Object ROOM_A = "roomA";
    private static final Object ROOM_B = "roomB";

    private FakeWorld world;
    private CutawayChangeTracker tracker;
    private final Set<Square>[] lastResults = newResultSets();
    private final List<int[]> edges = new ArrayList<>();
    private long changedAtSettle;

    @BeforeEach
    void setUp() {
        world = new FakeWorld();
        tracker = new CutawayChangeTracker(world);
        for (Set<Square> results : lastResults) {
            results.clear();
        }
        edges.clear();
        changedAtSettle = 0;
    }

    @Test
    void firstCallReplaysEveryVanillaInvalidation() {
        visit(north(1, 1, 0), north(9, 1, 0));

        assertEquals(List.of("0,0/0", "1,0/0"), world.invalidations);
        assertEquals(1, tracker.blanketCalls);
        assertEquals(0, tracker.invalidationsSkipped);
    }

    @Test
    void identicalResultSkipsEveryChunk() {
        visit(north(1, 1, 0), north(9, 1, 0));
        settle();

        visit(north(1, 1, 0), north(9, 1, 0));

        assertEquals(List.of(), world.invalidations);
        assertEquals(2, tracker.invalidationsSkipped);
        assertEquals(0, changedSinceSettle());
        assertEquals(1, tracker.blanketCalls);
    }

    @Test
    void changedSquareInvalidatesOnlyItsChunk() {
        visit(north(1, 1, 0), north(12, 1, 0));
        settle();

        visit(north(1, 1, 0), north(2, 1, 0), north(12, 1, 0));

        assertEquals(List.of("0,0/0"), world.invalidations);
        assertEquals(1, changedSinceSettle());
        assertEquals(1, tracker.invalidationsSkipped);
    }

    @Test
    void clearedSquareCountsAsChanged() {
        visit(north(1, 1, 0), north(2, 1, 0));
        settle();

        visit(north(1, 1, 0));

        assertEquals(List.of("0,0/0"), world.invalidations);
        assertEquals(1, changedSinceSettle());
    }

    @Test
    void flagBitSwapCountsAsChanged() {
        visit(north(1, 1, 0));
        settle();

        visit(west(1, 1, 0));

        assertEquals(List.of("0,0/0"), world.invalidations);
    }

    @Test
    void changeOnChunkBorderInvalidatesTheNeighbourChunk() {
        world.square(7, 3, 0);
        visit(north(12, 1, 0));
        settle();

        visit(north(12, 1, 0), north(8, 3, 0));

        assertEquals(List.of("1,0/0", "0,0/0"), world.invalidations);
    }

    @Test
    void changeInChunkCornerInvalidatesBothNeighbourChunks() {
        world.square(7, 8, 0);
        world.square(8, 7, 0);
        visit(north(12, 12, 0));
        settle();

        visit(north(12, 12, 0), west(8, 8, 0));

        assertEquals(
                new HashSet<>(List.of("1,1/0", "1,0/0", "0,1/0")),
                new HashSet<>(world.invalidations));
        assertEquals(3, world.invalidations.size());
    }

    @Test
    void changeOnAnotherLevelPairIsInvalidatedBesideTheFirstSeenLevel() {
        visit(north(1, 1, 0), north(2, 2, 2));
        settle();

        visit(north(1, 1, 0), north(2, 2, 2), north(3, 2, 2));

        assertEquals(List.of("0,0/1"), world.invalidations);
        assertEquals(1, tracker.invalidationsSkipped);
    }

    @Test
    void twoChangesInOneTextureInvalidateOnce() {
        visit(north(1, 1, 0));
        settle();

        visit(north(1, 1, 0), north(2, 1, 0), north(3, 1, 1));

        assertEquals(List.of("0,0/0"), world.invalidations);
        assertEquals(2, changedSinceSettle());
        assertEquals(0, tracker.invalidationsSkipped);
    }

    @Test
    void roomChangeReplaysEverything() {
        visit(north(1, 1, 0), north(9, 1, 0));
        settle();

        visitAs(0, ROOM_B, 0, 0L, north(1, 1, 0), north(9, 1, 0));

        assertEquals(List.of("0,0/0", "1,0/0"), world.invalidations);
        assertEquals(1, tracker.blanketViewpoint);
    }

    @Test
    void levelChangeReplaysEverything() {
        visit(north(1, 1, 0));
        settle();

        visitAs(0, ROOM_A, 1, 0L, north(1, 1, 0));

        assertEquals(List.of("0,0/0"), world.invalidations);
        assertEquals(1, tracker.blanketViewpoint);
    }

    @Test
    void roomLeftAndReenteredBetweenCallsReplaysEverything() {
        visit(north(1, 1, 0));
        settle();

        tracker.noteViewpoint(0, ROOM_A, 0);
        tracker.noteViewpoint(0, ROOM_B, 0);
        tracker.noteViewpoint(0, ROOM_A, 0);
        visit(north(1, 1, 0));

        assertEquals(List.of("0,0/0"), world.invalidations);
        assertEquals(1, tracker.blanketViewpoint);

        settle();
        tracker.noteViewpoint(0, ROOM_A, 0);
        visit(north(1, 1, 0));
        assertEquals(List.of(), world.invalidations);
    }

    @Test
    void externalWriteReplaysEverythingOnce() {
        visit(north(1, 1, 0));
        settle();

        visitAs(0, ROOM_A, 0, 1L, north(1, 1, 0));
        assertEquals(List.of("0,0/0"), world.invalidations);
        assertEquals(1, tracker.blanketExternalWrite);

        settle();
        visitAs(0, ROOM_A, 0, 1L, north(1, 1, 0));
        assertEquals(List.of(), world.invalidations);
    }

    @Test
    void chunkNewToTheCallIsReplayedEvenWithoutAFlagChange() {
        visit(north(1, 1, 0));
        settle();
        world.square(9, 1, 0).flags[0] = 1;

        visit(north(1, 1, 0), north(9, 1, 0));

        assertEquals(List.of("1,0/0"), world.invalidations);
        assertEquals(0, changedSinceSettle());
    }

    @Test
    void couldSeeFlipOnAFlaggedSquareReplaysItsChunkOnce() {
        visit(north(1, 1, 0), north(9, 1, 0));
        settle();

        tracker.noteCouldSeeFlip(world.square(9, 1, 0));
        visit(north(1, 1, 0), north(9, 1, 0));
        assertEquals(List.of("1,0/0"), world.invalidations);

        settle();
        visit(north(1, 1, 0), north(9, 1, 0));
        assertEquals(List.of(), world.invalidations);
    }

    @Test
    void couldSeeFlipBehindANorthWallReplaysTheWallChunk() {
        visit(north(3, 8, 0));
        settle();

        tracker.noteCouldSeeFlip(world.square(3, 7, 0));
        visit(north(3, 8, 0));

        assertEquals(List.of("0,1/0"), world.invalidations);
    }

    @Test
    void couldSeeFlipBehindAWestWallReplaysTheWallChunk() {
        visit(west(8, 3, 0));
        settle();

        tracker.noteCouldSeeFlip(world.square(7, 3, 0));
        visit(west(8, 3, 0));

        assertEquals(List.of("1,0/0"), world.invalidations);
    }

    @Test
    void couldSeeFlipAwayFromAnyCutIsIgnored() {
        visit(north(3, 8, 0));
        settle();

        tracker.noteCouldSeeFlip(world.square(4, 7, 0));
        tracker.noteCouldSeeFlip(world.square(2, 8, 0));
        tracker.noteCouldSeeFlip(null);
        visit(north(3, 8, 0));

        assertEquals(List.of(), world.invalidations);
    }

    @Test
    void tooManyCouldSeeFlipsReplayEverything() {
        visit(oneNorthSquarePerChunk(40));
        settle();

        for (Square square : oneNorthSquarePerChunk(40)) {
            tracker.noteCouldSeeFlip(square);
        }
        visit(oneNorthSquarePerChunk(40));

        assertEquals(40, world.invalidations.size());
        assertEquals(1, tracker.blanketCouldSeeOverflow);
    }

    @Test
    void edgeRecordIsDroppedOnceItsSourceChunkWasSeen() {
        world.square(1, 9, 0);
        edge(0, 1, 0);
        visit(north(1, 1, 0));
        assertEquals(List.of("0,0/0", "0,1/0"), world.invalidations);
        settle();

        edge(0, 1, 0);
        visit(north(1, 1, 0));

        assertEquals(List.of(), world.invalidations);
        assertEquals(2, tracker.invalidationsSkipped);
    }

    @Test
    void edgeRecordIsKeptWhenAnAdjacentTouchedChunkIsNew() {
        world.square(1, 9, 0);
        visit(north(12, 12, 0));
        settle();
        world.square(1, 1, 0).flags[0] = 1;

        edge(0, 1, 0);
        visit(north(12, 12, 0), north(1, 1, 0));

        assertEquals(List.of("0,0/0", "0,1/0"), world.invalidations);
    }

    @Test
    void edgeRecordIsDroppedWhenOnlyAFarChunkIsNew() {
        world.square(1, 9, 0);
        visit(north(1, 1, 0));
        settle();
        world.square(40, 40, 0).flags[0] = 1;

        edge(0, 1, 0);
        visit(north(1, 1, 0), north(40, 40, 0));

        assertEquals(List.of("5,5/0"), world.invalidations);
    }

    @Test
    void vanillaExceptionReplaysEveryRecordAndResetsMemory() {
        visit(north(1, 1, 0), north(9, 1, 0));
        settle();

        tracker.begin(0, OWNER, ROOM_A, 0, 0L);
        tracker.record(world.levels(world.chunk(0, 0), 0), 0, 2048L, CutawayChangeTracker.OWN);
        tracker.record(world.levels(world.chunk(1, 0), 0), 0, 2048L, CutawayChangeTracker.OWN);
        tracker.end(true);
        assertEquals(List.of("0,0/0", "1,0/0"), world.invalidations);

        settle();
        visit(north(1, 1, 0), north(9, 1, 0));
        assertEquals(List.of("0,0/0", "1,0/0"), world.invalidations);
        assertEquals(2, tracker.blanketCalls);
    }

    @Test
    void replayAllSurvivesAThrowingInvalidation() {
        visit(north(1, 1, 0), north(9, 1, 0));
        settle();

        tracker.begin(0, OWNER, ROOM_A, 0, 0L);
        tracker.record(world.levels(world.chunk(0, 0), 0), 0, 2048L, CutawayChangeTracker.OWN);
        tracker.record(world.levels(world.chunk(1, 0), 0), 0, 2048L, CutawayChangeTracker.OWN);
        world.throwOnInvalidate = 1;
        tracker.replayAll();

        assertEquals(List.of("1,0/0"), world.invalidations);
    }

    @Test
    void newOwnerReplaysEverything() {
        visit(north(1, 1, 0));
        settle();

        lastResults[0].clear();
        tracker.begin(0, new Object(), ROOM_A, 0, 0L);
        Square square = world.square(1, 1, 0);
        tracker.rememberBefore(square);
        tracker.record(world.levels(square.chunk, 0), 0, 2048L, CutawayChangeTracker.OWN);
        tracker.end(false);

        assertEquals(List.of("0,0/0"), world.invalidations);
        assertEquals(2, tracker.blanketCalls);
    }

    @Test
    void recycledChunkObjectAtNewCoordinatesIsTreatedAsNew() {
        visit(north(1, 1, 0));
        settle();

        world.chunk(0, 0).wx = 3;
        visit(north(1, 1, 0));

        assertEquals(List.of("3,0/0"), world.invalidations);
    }

    @Test
    void playersKeepSeparateMemory() {
        visit(north(1, 1, 0));
        settle();

        visitAs(1, ROOM_A, 0, 0L, north(1, 1, 0));
        assertEquals(List.of("0,0/0"), world.invalidations);
        assertEquals(2, tracker.blanketCalls);

        settle();
        visit(north(1, 1, 0));
        visitAs(1, ROOM_A, 0, 0L, north(1, 1, 0));
        assertEquals(List.of(), world.invalidations);
    }

    @Test
    void largeCallGrowsEveryTableAndStillFindsTheOneChange() {
        visit(northStrip());
        assertEquals(150, world.invalidations.size());
        settle();

        visit(northStrip());
        assertEquals(List.of(), world.invalidations);
        assertEquals(150, tracker.invalidationsSkipped);

        Square[] strip = northStrip();
        west(500, 1, 0);
        visit(strip);
        assertEquals(List.of("62,0/0"), world.invalidations);
        assertEquals(1, changedSinceSettle());
    }

    @Test
    void sameSquareInBothResultSetsIsRememberedOnce() {
        visit(north(1, 1, 0), west(1, 1, 0));
        settle();

        visit(north(1, 1, 0), west(1, 1, 0));

        assertEquals(3, world.square(1, 1, 0).flags[0]);
        assertEquals(List.of(), world.invalidations);
        assertEquals(0, changedSinceSettle());
    }

    @Test
    void countersAddUp() {
        visit(north(1, 1, 0), north(9, 1, 0));
        visit(north(1, 1, 0), north(9, 1, 0));
        visit(north(1, 1, 0), north(2, 1, 0), north(9, 1, 0));

        assertEquals(3, tracker.calls);
        assertEquals(3, tracker.invalidationsIssued);
        assertEquals(3, tracker.invalidationsSkipped);
        assertEquals(3, tracker.changedSquares);
    }

    private void settle() {
        world.invalidations.clear();
        changedAtSettle = tracker.changedSquares;
    }

    private long changedSinceSettle() {
        return tracker.changedSquares - changedAtSettle;
    }

    private Square[] oneNorthSquarePerChunk(int chunks) {
        Square[] squares = new Square[chunks];
        for (int i = 0; i < chunks; i++) {
            squares[i] = north(i * 8 + 1, 1, 0);
        }
        return squares;
    }

    /** 3000 squares over 150 chunks, which outgrows every preallocated table. */
    private Square[] northStrip() {
        Square[] squares = new Square[3000];
        for (int i = 0; i < squares.length; i++) {
            squares[i] = north(i % 1200, i / 1200, 0);
        }
        return squares;
    }

    private Square north(int x, int y, int z) {
        Square square = world.square(x, y, z);
        square.pendingBits |= 1;
        return square;
    }

    private Square west(int x, int y, int z) {
        Square square = world.square(x, y, z);
        square.pendingBits |= 2;
        return square;
    }

    private void edge(int wx, int wy, int level) {
        edges.add(new int[] {wx, wy, level});
    }

    private void visit(Square... results) {
        visitAs(0, ROOM_A, 0, 0L, results);
    }

    /** Mirrors steps 2 and 3 and the edge loop of the vanilla method. */
    private void visitAs(int player, Object room, int level, long epoch, Square... results) {
        tracker.begin(player, OWNER, room, level, epoch);
        Set<Object> invalidatedChunks = new HashSet<>();

        for (Square square : lastResults[player]) {
            tracker.rememberBefore(square);
            square.flags[player] = 0;
            recordOwn(player, square, invalidatedChunks);
        }
        Set<Square> current = new LinkedHashSet<>(Arrays.asList(results));
        for (int bit = 1; bit <= 2; bit++) {
            for (Square square : current) {
                if ((square.pendingBits & bit) == 0) {
                    continue;
                }
                tracker.rememberBefore(square);
                square.flags[player] |= bit;
                recordOwn(player, square, invalidatedChunks);
            }
        }
        for (int[] edge : edges) {
            Object levels = world.levels(world.chunk(edge[0], edge[1]), player);
            tracker.record(levels, edge[2], 2048L, CutawayChangeTracker.EDGE);
        }
        tracker.end(false);

        for (Square square : current) {
            square.pendingBits = 0;
        }
        lastResults[player].clear();
        lastResults[player].addAll(current);
        edges.clear();
    }

    private void recordOwn(int player, Square square, Set<Object> invalidatedChunks) {
        if (invalidatedChunks.add(square.chunk)) {
            tracker.record(
                    world.levels(square.chunk, player), square.z, 2048L, CutawayChangeTracker.OWN);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<Square>[] newResultSets() {
        Set<Square>[] sets = new Set[4];
        for (int i = 0; i < sets.length; i++) {
            sets[i] = new LinkedHashSet<>();
        }
        return sets;
    }

    private static final class Square {
        final int x;
        final int y;
        final int z;
        final Chunk chunk;
        final int[] flags = new int[4];
        int pendingBits;

        Square(int x, int y, int z, Chunk chunk) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.chunk = chunk;
        }
    }

    private static final class Chunk {
        int wx;
        int wy;
        final Levels[] levels = new Levels[4];
    }

    private static final class Levels {
        final Chunk chunk;

        Levels(Chunk chunk) {
            this.chunk = chunk;
        }
    }

    private static final class FakeWorld implements CutawayChangeTracker.World {

        final Map<String, Square> squares = new HashMap<>();
        final Map<String, Chunk> chunks = new HashMap<>();
        final List<String> invalidations = new ArrayList<>();
        int throwOnInvalidate;

        Chunk chunk(int wx, int wy) {
            return chunks.computeIfAbsent(
                    wx + "," + wy,
                    key -> {
                        Chunk chunk = new Chunk();
                        chunk.wx = wx;
                        chunk.wy = wy;
                        return chunk;
                    });
        }

        Square square(int x, int y, int z) {
            return squares.computeIfAbsent(
                    x + "," + y + "," + z,
                    key -> new Square(x, y, z, chunk(Math.floorDiv(x, 8), Math.floorDiv(y, 8))));
        }

        Levels levels(Chunk chunk, int player) {
            if (chunk.levels[player] == null) {
                chunk.levels[player] = new Levels(chunk);
            }
            return chunk.levels[player];
        }

        @Override
        public int playerCount() {
            return 4;
        }

        @Override
        public int flag(Object square, int playerIndex) {
            return ((Square) square).flags[playerIndex];
        }

        @Override
        public int level(Object square) {
            return ((Square) square).z;
        }

        @Override
        public Object chunkOf(Object square) {
            return ((Square) square).chunk;
        }

        @Override
        public Object neighbour(Object square, int direction) {
            Square s = (Square) square;
            int dx =
                    direction == CutawayChangeTracker.WEST
                            ? -1
                            : direction == CutawayChangeTracker.EAST ? 1 : 0;
            int dy =
                    direction == CutawayChangeTracker.NORTH
                            ? -1
                            : direction == CutawayChangeTracker.SOUTH ? 1 : 0;
            return squares.get((s.x + dx) + "," + (s.y + dy) + "," + s.z);
        }

        @Override
        public int chunkX(Object chunk) {
            return ((Chunk) chunk).wx;
        }

        @Override
        public int chunkY(Object chunk) {
            return ((Chunk) chunk).wy;
        }

        @Override
        public Object renderLevels(Object chunk, int playerIndex) {
            return levels((Chunk) chunk, playerIndex);
        }

        @Override
        public Object chunkOfRenderLevels(Object renderLevels) {
            return ((Levels) renderLevels).chunk;
        }

        @Override
        public int textureKey(Object renderLevels, int level) {
            return Math.floorDiv(level, 2);
        }

        @Override
        public void invalidate(Object renderLevels, int level, long dirtyFlags) {
            if (throwOnInvalidate > 0 && throwOnInvalidate-- == 1) {
                throw new IllegalStateException("boom");
            }
            Chunk chunk = ((Levels) renderLevels).chunk;
            invalidations.add(chunk.wx + "," + chunk.wy + "/" + Math.floorDiv(level, 2));
        }
    }
}
