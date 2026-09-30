package io.pzstorm.storm.advice.puddlebatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayDeque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Lookup, hook marks and lifetime rules of {@link PuddleBatchTable}. */
class PuddleBatchTableTest implements UnitTest {

    private static final Object CHUNK_A = new Object();
    private static final Object CHUNK_B = new Object();

    private PuddleBatchTable table;
    private ArrayDeque<PuddleBatch> retired;

    @BeforeEach
    void setUp() {
        table = new PuddleBatchTable();
        retired = new ArrayDeque<>();
    }

    /** A batch as the renderer leaves it after a build: no hook flag set. */
    private PuddleBatch clean(Object chunk, int playerIndex, int z, int frame) {
        PuddleBatch batch = table.getOrCreate(chunk, playerIndex, z, frame);
        batch.beginBuild();
        return batch;
    }

    @Test
    void oneBatchPerChunkPlayerAndLevel() {
        PuddleBatch batch = table.getOrCreate(CHUNK_A, 0, 0, 1);

        assertNotNull(batch);
        assertSame(batch, table.getOrCreate(CHUNK_A, 0, 0, 2));
        assertSame(batch, table.get(CHUNK_A, 0, 0));
        assertNotSame(batch, table.getOrCreate(CHUNK_A, 1, 0, 2));
        assertNotSame(batch, table.getOrCreate(CHUNK_A, 0, 1, 2));
        assertNotSame(batch, table.getOrCreate(CHUNK_A, 0, -1, 2));
        assertNotSame(batch, table.getOrCreate(CHUNK_B, 0, 0, 2));
        assertEquals(2, table.chunkCount());
        assertFalse(table.isEmpty());
    }

    @Test
    void outOfRangeSlotsYieldNoBatch() {
        assertNull(table.getOrCreate(CHUNK_A, PuddleBatchTable.PLAYERS, 0, 1));
        assertNull(table.getOrCreate(CHUNK_A, -1, 0, 1));
        assertNull(table.getOrCreate(CHUNK_A, 0, PuddleBatchTable.LEVELS / 2, 1));
        assertNull(table.getOrCreate(CHUNK_A, 0, -PuddleBatchTable.LEVEL_OFFSET - 1, 1));
        assertNotNull(table.getOrCreate(CHUNK_A, 0, -PuddleBatchTable.LEVEL_OFFSET, 1));
        assertNotNull(table.getOrCreate(CHUNK_A, 0, PuddleBatchTable.LEVELS / 2 - 1, 1));
        assertNull(table.get(CHUNK_B, 0, 0));
    }

    @Test
    void marksReachOnlyTheirOwnBatch() {
        PuddleBatch ground = clean(CHUNK_A, 0, 0, 1);
        PuddleBatch upper = clean(CHUNK_A, 0, 1, 1);
        PuddleBatch otherPlayer = clean(CHUNK_A, 1, 0, 1);
        PuddleBatch otherChunk = clean(CHUNK_B, 0, 0, 1);

        table.markInvalid(CHUNK_A, 0, 0);
        table.markLightsDirty(CHUNK_A, 0, 1);

        assertTrue(ground.isInvalid());
        assertFalse(ground.isLightsDirty());
        assertFalse(upper.isInvalid());
        assertTrue(upper.isLightsDirty());
        assertFalse(otherPlayer.isInvalid() || otherPlayer.isLightsDirty());
        assertFalse(otherChunk.isInvalid() || otherChunk.isLightsDirty());
    }

    @Test
    void marksOnUnknownOrOutOfRangeSlotsDoNothing() {
        table.markInvalid(CHUNK_A, 0, 0);
        table.markLightsDirty(CHUNK_A, 0, 0);
        table.markChunkInvalid(CHUNK_A);
        table.markInvalid(CHUNK_A, 9, 99);

        assertTrue(table.isEmpty(), "a mark must not create an entry");
    }

    @Test
    void chunkResetInvalidatesEveryBatchOfThatChunk() {
        PuddleBatch a0 = clean(CHUNK_A, 0, 0, 1);
        PuddleBatch a1 = clean(CHUNK_A, 3, 5, 1);
        PuddleBatch b0 = clean(CHUNK_B, 0, 0, 1);

        table.markChunkInvalid(CHUNK_A);

        assertTrue(a0.isInvalid());
        assertTrue(a1.isInvalid());
        assertFalse(b0.isInvalid());
    }

    @Test
    void invalidateAllReachesEveryBatch() {
        PuddleBatch a = clean(CHUNK_A, 0, 0, 1);
        PuddleBatch b = clean(CHUNK_B, 2, -3, 1);

        table.invalidateAll();

        assertTrue(a.isInvalid());
        assertTrue(b.isInvalid());
        assertFalse(a.isDropped());
    }

    @Test
    void sweepDropsOnlyChunksUnseenForLongerThanTheAge() {
        PuddleBatch old = clean(CHUNK_A, 0, 0, 100);
        PuddleBatch oldUpper = clean(CHUNK_A, 0, 1, 100);
        PuddleBatch fresh = clean(CHUNK_B, 0, 0, 150);

        assertEquals(0, table.sweep(150, 50, retired));
        assertEquals(2, table.sweep(151, 50, retired));

        assertTrue(old.isDropped());
        assertTrue(oldUpper.isDropped());
        assertFalse(fresh.isDropped());
        assertTrue(retired.contains(old) && retired.contains(oldUpper));
        assertNull(table.get(CHUNK_A, 0, 0));
        assertSame(fresh, table.get(CHUNK_B, 0, 0));
        assertNotSame(old, table.getOrCreate(CHUNK_A, 0, 0, 151));
    }

    @Test
    void sweepDropsEntriesStampedInTheFuture() {
        PuddleBatch batch = clean(CHUNK_A, 0, 0, 5000);

        assertEquals(1, table.sweep(10, 2048, retired));
        assertTrue(batch.isDropped());
        assertTrue(table.isEmpty());
    }

    @Test
    void touchingAChunkKeepsItAlive() {
        clean(CHUNK_A, 0, 0, 100);
        table.getOrCreate(CHUNK_A, 0, 0, 160);

        assertEquals(0, table.sweep(200, 50, retired));
        assertFalse(table.isEmpty());
    }

    @Test
    void dropAllRetiresEveryBatch() {
        PuddleBatch a = clean(CHUNK_A, 0, 0, 1);
        PuddleBatch b = clean(CHUNK_B, 1, 2, 1);

        assertEquals(2, table.dropAll(retired));

        assertTrue(table.isEmpty());
        assertTrue(a.isDropped() && b.isDropped());
        assertEquals(2, retired.size());
    }
}
