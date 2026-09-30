package io.pzstorm.storm.advice.puddlebatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Bookkeeping rules of {@link PuddleBatch}: rebuild triggers, in-place patches and uploads. */
class PuddleBatchTest implements UnitTest {

    private static final int INTERVAL = 60;
    private static final int V = PuddleBatch.FLOATS_PER_VERTEX;

    /** Packed squares whose float {@code n} holds the value {@code n}. */
    private static float[] packed(int squares) {
        float[] data = new float[squares * PuddleBatch.FLOATS_PER_SQUARE];
        for (int n = 0; n < data.length; n++) {
            data[n] = n;
        }
        return data;
    }

    private static PuddleBatch built(int squares, int listSize, long mask, int frame) {
        PuddleBatch batch = new PuddleBatch();
        batch.beginBuild();
        batch.ensureCapacity(squares);
        for (int i = 0; i < squares; i++) {
            batch.squares[i] = "square" + i;
        }
        batch.load(packed(squares), squares, 0.0F, 0.0F);
        batch.finishBuild(listSize, mask, 10, 20, frame, INTERVAL, 7);
        return batch;
    }

    @Test
    void flagMaskTakesBitZeroOfEachSquare() {
        byte[] flags = new byte[64];
        flags[0] = 1;
        flags[5] = 3;
        flags[6] = 2;
        flags[63] = (byte) 0x81;

        assertEquals(1L | 1L << 5 | 1L << 63, PuddleBatch.flagMask(flags));
        assertEquals(0L, PuddleBatch.flagMask(new byte[64]));
    }

    @Test
    void staggerStaysInsideTheIntervalAndSpreads() {
        Set<Integer> seen = new HashSet<>();
        for (int wx = -40; wx < 40; wx++) {
            for (int wy = -40; wy < 40; wy++) {
                int stagger = PuddleBatch.stagger(wx, wy, 0, INTERVAL);
                assertTrue(stagger >= 0 && stagger < INTERVAL, "stagger " + stagger);
                assertEquals(stagger, PuddleBatch.stagger(wx, wy, 0, INTERVAL));
                seen.add(stagger);
            }
        }
        assertTrue(seen.size() > INTERVAL / 2, "stagger must spread; got " + seen.size());
        assertEquals(0, PuddleBatch.stagger(3, 4, 1, 1));
        assertEquals(0, PuddleBatch.stagger(3, 4, 1, 0));
    }

    @Test
    void aNewBatchNeedsABuild() {
        assertEquals(PuddleBatch.REBUILD_INVALID, new PuddleBatch().rebuildCause(0, 0L, 0));
    }

    @Test
    void aBuiltBatchIsReusedUntilAnInputChanges() {
        PuddleBatch batch = built(3, 5, 0b1011L, 100);

        assertEquals(PuddleBatch.REUSE, batch.rebuildCause(5, 0b1011L, 100));
        assertEquals(PuddleBatch.REUSE, batch.rebuildCause(5, 0b1011L, 100 + INTERVAL + 6));
        assertEquals(PuddleBatch.REBUILD_LIST, batch.rebuildCause(6, 0b1011L, 101));
        assertEquals(PuddleBatch.REBUILD_MASK, batch.rebuildCause(5, 0b1010L, 101));
        assertEquals(
                PuddleBatch.REBUILD_EXPIRED, batch.rebuildCause(5, 0b1011L, 100 + INTERVAL + 7));
        assertEquals(PuddleBatch.REBUILD_EXPIRED, batch.rebuildCause(5, 0b1011L, 99));
    }

    @Test
    void anInvalidMarkWinsOverEveryOtherCause() {
        PuddleBatch batch = built(3, 5, 1L, 100);
        batch.invalid = true;

        assertEquals(PuddleBatch.REBUILD_INVALID, batch.rebuildCause(6, 2L, 1000));
    }

    @Test
    void beginBuildClearsTheHookFlags() {
        PuddleBatch batch = built(1, 1, 1L, 0);
        batch.invalid = true;
        batch.lightsDirty = true;

        batch.beginBuild();

        assertFalse(batch.isInvalid());
        assertFalse(batch.isLightsDirty());
        assertEquals(0, batch.count());
    }

    @Test
    void aMarkSetDuringTheBuildSurvivesIt() {
        PuddleBatch batch = new PuddleBatch();
        batch.beginBuild();
        batch.invalid = true;
        batch.lightsDirty = true;
        batch.load(packed(1), 1, 0.0F, 0.0F);
        batch.finishBuild(1, 1L, 0, 0, 5, INTERVAL, 0);

        assertTrue(batch.isInvalid());
        assertTrue(batch.isLightsDirty());
        assertEquals(PuddleBatch.REBUILD_INVALID, batch.rebuildCause(1, 1L, 6));
    }

    @Test
    void loadRemovesTheJiggleFromPositionsOnly() {
        PuddleBatch batch = new PuddleBatch();
        batch.beginBuild();

        batch.load(packed(2), 2, 0.25F, -0.5F);

        float[] expected = packed(2);
        for (int n = 0; n < expected.length; n += V) {
            expected[n + PuddleBatch.OFFSET_X] -= 0.25F;
            expected[n + PuddleBatch.OFFSET_Y] += 0.5F;
        }
        for (int n = 0; n < expected.length; n++) {
            assertEquals(expected[n], batch.data()[n], "float " + n);
        }
        assertEquals(2, batch.count());
    }

    @Test
    void finishBuildRecordsTheInputsAndQueuesAnUpload() {
        PuddleBatch batch = built(2, 9, 0xF0L, 100);

        assertTrue(batch.isDirty());
        assertEquals(1, batch.version());
        assertEquals(100 + INTERVAL + 7, batch.expiryFrame());
        assertEquals(10, batch.camChunkX);
        assertEquals(20, batch.camChunkY);
    }

    @Test
    void failBuildLeavesNothingToDrawAndAsksForAnotherBuild() {
        PuddleBatch batch = built(2, 2, 1L, 0);

        batch.failBuild();

        assertEquals(0, batch.count());
        assertTrue(batch.isInvalid());
        assertEquals(null, batch.squares[0]);
    }

    @Test
    void setLightReportsOnlyRealChanges() {
        PuddleBatch batch = built(2, 2, 1L, 0);
        int index = PuddleBatch.FLOATS_PER_SQUARE + 2 * V + PuddleBatch.OFFSET_COLOR;

        // Light values are colour bits, and many are NaN bit patterns, so they compare as ints.
        for (int bits : new int[] {-1, 0xFF8A0000, 0xFF123456, 0x7FC00000, 0}) {
            assertTrue(batch.setLight(1, 2, bits), Integer.toHexString(bits));
            assertFalse(batch.setLight(1, 2, bits), Integer.toHexString(bits));
            assertEquals(bits, Float.floatToRawIntBits(batch.data()[index]));
        }
        assertEquals(index - 1.0F, batch.data()[index - 1], "neighbours stay untouched");
        assertEquals(index + 1.0F, batch.data()[index + 1], "neighbours stay untouched");
    }

    @Test
    void shiftDepthMovesEveryVertexDepthByOneConstant() {
        PuddleBatch batch = built(2, 2, 1L, 0);

        assertTrue(batch.shiftDepth(0.5F, 11, 21));

        float[] expected = packed(2);
        for (int n = PuddleBatch.OFFSET_DEPTH; n < expected.length; n += V) {
            expected[n] += 0.5F;
        }
        for (int n = 0; n < expected.length; n++) {
            assertEquals(expected[n], batch.data()[n], "float " + n);
        }
        assertEquals(11, batch.camChunkX);
        assertEquals(21, batch.camChunkY);
    }

    @Test
    void shiftDepthWithNoChangeOnlyMovesTheCameraChunk() {
        PuddleBatch batch = built(2, 2, 1L, 0);
        PuddleBatch empty = built(0, 2, 1L, 0);

        assertFalse(batch.shiftDepth(0.0F, 12, 22));
        assertEquals(12, batch.camChunkX);
        assertEquals(22, batch.camChunkY);
        assertFalse(empty.shiftDepth(0.5F, 12, 22));
        assertEquals(12, empty.camChunkX);
    }

    @Test
    void aSnapshotCarriesTheDataAndItsCausesOnce() {
        PuddleBatch batch = built(2, 2, 1L, 0);
        batch.markDirty(PuddleBatch.CAUSE_LIGHT);
        float[] snapshot = new float[PuddleBatch.MAX_SQUARES * PuddleBatch.FLOATS_PER_SQUARE];

        assertTrue(batch.needsUpload(1L));
        int causes = batch.takeSnapshot(snapshot, 1L);

        assertEquals(PuddleBatch.CAUSE_REBUILD | PuddleBatch.CAUSE_LIGHT, causes);
        assertEquals(2, batch.version());
        for (int n = 0; n < 2 * PuddleBatch.FLOATS_PER_SQUARE; n++) {
            assertEquals(batch.data()[n], snapshot[n]);
        }
        assertFalse(batch.isDirty());
        assertFalse(batch.needsUpload(2L));
    }

    @Test
    void anUnconfirmedUploadIsSentAgainAfterTheRetryWindow() {
        PuddleBatch batch = built(1, 1, 1L, 0);
        float[] snapshot = new float[PuddleBatch.FLOATS_PER_SQUARE];
        batch.takeSnapshot(snapshot, 100L);

        assertFalse(batch.needsUpload(100L + PuddleBatch.UPLOAD_RETRY_PASSES));
        assertTrue(batch.needsUpload(101L + PuddleBatch.UPLOAD_RETRY_PASSES));
        assertEquals(0, batch.takeSnapshot(snapshot, 101L + PuddleBatch.UPLOAD_RETRY_PASSES));

        batch.uploadedVersion = batch.version();

        assertFalse(batch.needsUpload(10_000L));
    }

    @Test
    void dropLetsGoOfSquaresAndData() {
        PuddleBatch batch = built(2, 2, 1L, 0);

        batch.drop();

        assertTrue(batch.isDropped());
        assertTrue(batch.isInvalid());
        assertEquals(0, batch.count());
        assertEquals(0, batch.data().length);
        assertEquals(0, batch.squares.length);
    }
}
