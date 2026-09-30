package io.pzstorm.storm.advice.persistentvbo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link SpriteRingSizing}: the pure decision (defaults, clamps, never-shrink, garbage
 * input, capability), the index-range bounds that make an enlarged buffer safe for the vanilla
 * unsigned-short indices, and the latched entry points the woven {@code create()} calls.
 */
class SpriteRingSizingTest implements UnitTest {

    private static final long MIB = 1L << 20;

    private String bytesBefore;
    private String buffersBefore;

    @BeforeEach
    void clearState() {
        bytesBefore = System.clearProperty(SpriteRingSizing.BUFFER_BYTES_PROPERTY);
        buffersBefore = System.clearProperty(SpriteRingSizing.BUFFERS_PROPERTY);
        SpriteRingSizing.reset();
    }

    @AfterEach
    void restoreState() {
        restore(SpriteRingSizing.BUFFER_BYTES_PROPERTY, bytesBefore);
        restore(SpriteRingSizing.BUFFERS_PROPERTY, buffersBefore);
        SpriteRingSizing.reset();
    }

    @Test
    void defaultsReplaceBothVanillaShapes() {
        assertArrayEquals(
                new long[] {MIB, 16}, SpriteRingSizing.decide(65536L, 128, null, null, true));
        assertArrayEquals(
                new long[] {MIB, 16}, SpriteRingSizing.decide(262144L, 256, null, null, true));
    }

    @Test
    void bufferBytesClampToTheUnsignedShortIndexRange() {
        assertEquals(2359296L, SpriteRingSizing.MAX_BUFFER_BYTES);
        assertEquals(65536L, SpriteRingSizing.MAX_BUFFER_BYTES / SpriteRingSizing.VERTEX_BYTES);
        assertTrue(SpriteRingSizing.DEFAULT_BUFFER_BYTES / SpriteRingSizing.VERTEX_BYTES <= 32767);
        assertTrue(SpriteRingSizing.MAX_BUFFER_BYTES <= PersistentVboSupport.MAX_SIZE);

        long[] decided = SpriteRingSizing.decide(65536L, 128, Long.toString(4 * MIB), null, true);
        assertEquals(SpriteRingSizing.MAX_BUFFER_BYTES, decided[0]);
    }

    @Test
    void neverShrinksAVanillaBufferThatIsAlreadyLargeEnough() {
        assertArrayEquals(
                new long[] {MIB, 128}, SpriteRingSizing.decide(MIB, 128, null, null, true));
        assertArrayEquals(
                new long[] {2 * MIB, 128}, SpriteRingSizing.decide(2 * MIB, 128, null, null, true));
        assertArrayEquals(
                new long[] {65536L, 128}, SpriteRingSizing.decide(65536L, 128, "4096", "16", true));
    }

    @Test
    void bufferCountClampsToFloorVanillaAndTotalBytes() {
        assertArrayEquals(
                new long[] {MIB, 32}, SpriteRingSizing.decide(65536L, 128, null, "128", true));
        assertArrayEquals(
                new long[] {MIB, 8}, SpriteRingSizing.decide(65536L, 128, null, "2", true));
        assertArrayEquals(
                new long[] {MIB, 8}, SpriteRingSizing.decide(65536L, 128, null, "-7", true));
        assertArrayEquals(
                new long[] {MIB, 4}, SpriteRingSizing.decide(65536L, 4, null, "16", true));
        assertArrayEquals(
                new long[] {SpriteRingSizing.MAX_BUFFER_BYTES, 14},
                SpriteRingSizing.decide(65536L, 128, Long.toString(4 * MIB), "128", true));
    }

    @Test
    void garbageStringsGiveTheDefaults() {
        for (String garbage :
                new String[] {
                    "", "  ", "abc", "1e6", "0x100000", "1048576b", "99999999999999999999"
                }) {
            assertArrayEquals(
                    new long[] {MIB, 16},
                    SpriteRingSizing.decide(65536L, 128, garbage, garbage, true),
                    "input '" + garbage + "'");
        }
    }

    @Test
    void notCapableLeavesBothVanilla() {
        assertArrayEquals(
                new long[] {65536L, 128}, SpriteRingSizing.decide(65536L, 128, null, null, false));
        assertArrayEquals(
                new long[] {262144L, 256},
                SpriteRingSizing.decide(262144L, 256, "2000000", "8", false));

        SpriteRingSizing.capableOverride = Boolean.FALSE;
        assertEquals(65536L, SpriteRingSizing.bufferSize(65536L));
        assertEquals(128, SpriteRingSizing.numBuffers(128));
    }

    @Test
    void indexBufferHoldsEveryQuadTheVertexBufferHolds() {
        for (long vanilla : new long[] {65536L, 262144L}) {
            for (long request = 0; request <= 5 * MIB; request += 7) {
                long bytes = SpriteRingSizing.clampBufferBytes(vanilla, request);
                long vertices = bytes / SpriteRingSizing.VERTEX_BYTES;
                long indexShorts = vertices * 3 / 2;
                if (vertices > 65536 || indexShorts < 6 * (vertices / 4)) {
                    fail("request " + request + " gives " + vertices + " vertices per buffer");
                }
            }
        }
    }

    @Test
    void noInputThrows() {
        long[] sizes = {Long.MIN_VALUE, -1, 0, 1, 35, 36, 65536, 262144, MIB, Long.MAX_VALUE};
        int[] counts = {Integer.MIN_VALUE, -1, 0, 1, 7, 8, 128, 256, Integer.MAX_VALUE};
        String[] requests = {
            null,
            "",
            " ",
            "-1",
            "0",
            "1",
            "16",
            "1048576",
            Long.toString(Long.MAX_VALUE),
            Long.toString(Long.MIN_VALUE)
        };
        for (long size : sizes) {
            for (int count : counts) {
                for (String requestedBytes : requests) {
                    for (String requestedBuffers : requests) {
                        long[] decided =
                                assertDoesNotThrow(
                                        () ->
                                                SpriteRingSizing.decide(
                                                        size,
                                                        count,
                                                        requestedBytes,
                                                        requestedBuffers,
                                                        true));
                        String inputs =
                                size
                                        + " x "
                                        + count
                                        + ", '"
                                        + requestedBytes
                                        + "', '"
                                        + requestedBuffers
                                        + "'";
                        assertTrue(decided[0] >= size, "size shrank for " + inputs);
                        assertTrue(
                                decided[0] <= Math.max(size, SpriteRingSizing.MAX_BUFFER_BYTES),
                                "size above the index range for " + inputs);
                        assertTrue(decided[1] <= count, "count grew for " + inputs);
                        if (decided[0] == size) {
                            assertEquals(count, decided[1], "count changed alone for " + inputs);
                        }
                    }
                }
            }
        }
    }

    @Test
    void entryPointsApplyTheDefaultsAndRecordBothPairs() {
        SpriteRingSizing.capableOverride = Boolean.TRUE;

        assertEquals(MIB, SpriteRingSizing.bufferSize(65536L));
        assertEquals(16, SpriteRingSizing.numBuffers(128));

        assertEquals(65536L, SpriteRingSizing.vanillaBufferSize);
        assertEquals(MIB, SpriteRingSizing.appliedBufferSize);
        assertEquals(128, SpriteRingSizing.vanillaNumBuffers);
        assertEquals(16, SpriteRingSizing.appliedNumBuffers);

        assertEquals(MIB, SpriteRingSizing.bufferSize(65536L), "the decision is latched");
        assertEquals(16, SpriteRingSizing.numBuffers(128), "the decision is latched");
    }

    @Test
    void entryPointsReadAndClampTheProperties() {
        System.setProperty(SpriteRingSizing.BUFFER_BYTES_PROPERTY, Long.toString(4 * MIB));
        System.setProperty(SpriteRingSizing.BUFFERS_PROPERTY, "128");
        SpriteRingSizing.capableOverride = Boolean.TRUE;

        assertEquals(SpriteRingSizing.MAX_BUFFER_BYTES, SpriteRingSizing.bufferSize(262144L));
        assertEquals(14, SpriteRingSizing.numBuffers(256));
    }

    @Test
    void entryPointsFallBackToTheDefaultsOnGarbageProperties() {
        System.setProperty(SpriteRingSizing.BUFFER_BYTES_PROPERTY, "big");
        System.setProperty(SpriteRingSizing.BUFFERS_PROPERTY, "few");
        SpriteRingSizing.capableOverride = Boolean.TRUE;

        assertEquals(MIB, SpriteRingSizing.bufferSize(65536L));
        assertEquals(16, SpriteRingSizing.numBuffers(128));
    }

    @Test
    void countBeforeSizeLeavesBothVanilla() {
        SpriteRingSizing.capableOverride = Boolean.TRUE;

        assertEquals(128, SpriteRingSizing.numBuffers(128));
        assertEquals(65536L, SpriteRingSizing.bufferSize(65536L));
        assertEquals(128, SpriteRingSizing.numBuffers(128));
    }

    @Test
    void entryPointsNeverShrink() {
        SpriteRingSizing.capableOverride = Boolean.TRUE;

        assertEquals(2 * MIB, SpriteRingSizing.bufferSize(2 * MIB));
        assertEquals(128, SpriteRingSizing.numBuffers(128));
    }

    /** No GL context is current in a unit test, so the capability predicate itself throws. */
    @Test
    void capabilityFailureIsCaughtAndLeavesBothVanilla() {
        assertEquals(65536L, assertDoesNotThrow(() -> SpriteRingSizing.bufferSize(65536L)));
        assertEquals(128, assertDoesNotThrow(() -> SpriteRingSizing.numBuffers(128)));
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
