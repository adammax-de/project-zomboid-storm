package io.pzstorm.storm.advice.persistentvbo;

import io.pzstorm.storm.logging.StormLogger;

/**
 * Sizing of the sprite ring, called from the two stores {@code SpriteRendererRingBufferSizingPatch}
 * wraps in {@code SpriteRenderer.RingBuffer.create()}. {@link #bufferSize(long)} receives the
 * per-buffer byte size vanilla chose and {@link #numBuffers(int)} the buffer count; each returns
 * the value to store. Vanilla derives the vertex capacity, the index buffer size and every array
 * from those two fields.
 *
 * <p>The decision is made once, on the first {@link #bufferSize(long)} call. The size grows only
 * when the GL context can persistently map buffers and the configured size is above vanilla's. The
 * count drops only when the size grew in the same decision, so a count asked for before a size
 * leaves both vanilla. The size never exceeds {@link #MAX_BUFFER_BYTES}, because the ring indexes
 * vertices with unsigned shorts.
 *
 * <p>Neither entry point lets a throwable escape. A failure latches vanilla sizing and returns the
 * argument. The class references nothing beyond {@code java.*}, the Storm logger and the capability
 * predicate, and touches the last two only inside {@code try} blocks, so the raw {@code
 * INVOKESTATIC} in {@code create()} cannot hit a link error.
 */
public final class SpriteRingSizing {

    public static final String BUFFER_BYTES_PROPERTY =
            "storm.experimental.clientperf.spriteRingBufferBytes";
    public static final String BUFFERS_PROPERTY = "storm.experimental.clientperf.spriteRingBuffers";

    public static final long DEFAULT_BUFFER_BYTES = 1L << 20;
    public static final int DEFAULT_BUFFERS = 16;
    public static final int VERTEX_BYTES = 36;

    /** 65536 vertices, the most an unsigned short index can address. */
    public static final long MAX_BUFFER_BYTES = 65536L * VERTEX_BYTES;

    public static final int MIN_BUFFERS = 8;
    public static final long MAX_RING_BYTES = 32L << 20;

    public static volatile long vanillaBufferSize;
    public static volatile long appliedBufferSize;
    public static volatile int vanillaNumBuffers;
    public static volatile int appliedNumBuffers;

    private static final int UNDECIDED = 0;
    private static final int SIZE_ENLARGED = 1;
    private static final int ENLARGED = 2;
    private static final int VANILLA = 3;

    private static int state;
    private static String requestedBuffers;

    /** Test seam. {@code null} asks the GL context. */
    static Boolean capableOverride;

    private SpriteRingSizing() {}

    public static long bufferSize(long vanilla) {
        try {
            if (state == UNDECIDED) {
                decideSize(vanilla);
            }
            return state == VANILLA ? vanilla : appliedBufferSize;
        } catch (Throwable t) {
            state = VANILLA;
            warn("Sprite ring sizing failed; keeping vanilla sizing", t);
            return vanilla;
        }
    }

    public static int numBuffers(int vanilla) {
        try {
            if (state == ENLARGED) {
                return appliedNumBuffers;
            }
            if (state != SIZE_ENLARGED) {
                state = VANILLA;
                vanillaNumBuffers = vanilla;
                appliedNumBuffers = vanilla;
                return vanilla;
            }
            int applied = clampBuffers(vanilla, parseBuffers(requestedBuffers), appliedBufferSize);
            vanillaNumBuffers = vanilla;
            appliedNumBuffers = applied;
            state = ENLARGED;
            info(
                    "Sprite ring sized to {} buffers of {} bytes (vanilla {} buffers of {} bytes)",
                    applied,
                    appliedBufferSize,
                    vanilla,
                    vanillaBufferSize);
            return applied;
        } catch (Throwable t) {
            state = VANILLA;
            warn("Sprite ring sizing failed; keeping the vanilla buffer count", t);
            return vanilla;
        }
    }

    /**
     * The whole decision as a pure function. Returns {@code {bufferBytes, buffers}}, equal to the
     * vanilla pair whenever the size does not grow.
     */
    static long[] decide(
            long vanillaBytes,
            int vanillaBuffers,
            String requestedBytes,
            String requestedBuffers,
            boolean capable) {
        long bytes = clampBufferBytes(vanillaBytes, parseBufferBytes(requestedBytes));
        if (!capable || bytes <= vanillaBytes) {
            return new long[] {vanillaBytes, vanillaBuffers};
        }
        return new long[] {
            bytes, clampBuffers(vanillaBuffers, parseBuffers(requestedBuffers), bytes)
        };
    }

    static long parseBufferBytes(String raw) {
        return parse(raw, DEFAULT_BUFFER_BYTES, BUFFER_BYTES_PROPERTY);
    }

    static int parseBuffers(String raw) {
        long parsed = parse(raw, DEFAULT_BUFFERS, BUFFERS_PROPERTY);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, parsed));
    }

    static long clampBufferBytes(long vanilla, long requested) {
        return Math.max(vanilla, Math.min(requested, MAX_BUFFER_BYTES));
    }

    static int clampBuffers(int vanilla, int requested, long bufferBytes) {
        if (bufferBytes <= 0) {
            return vanilla;
        }
        long count = Math.min(vanilla, Math.max(MIN_BUFFERS, requested));
        return (int) Math.min(count, Math.max(1L, MAX_RING_BYTES / bufferBytes));
    }

    static void reset() {
        state = UNDECIDED;
        requestedBuffers = null;
        capableOverride = null;
        vanillaBufferSize = 0;
        appliedBufferSize = 0;
        vanillaNumBuffers = 0;
        appliedNumBuffers = 0;
    }

    private static void decideSize(long vanilla) {
        vanillaBufferSize = vanilla;
        appliedBufferSize = vanilla;
        long wanted =
                clampBufferBytes(
                        vanilla, parseBufferBytes(System.getProperty(BUFFER_BYTES_PROPERTY)));
        if (wanted <= vanilla) {
            state = VANILLA;
            info("Sprite ring keeps vanilla sizing (buffers are already {} bytes)", vanilla);
            return;
        }
        Boolean override = capableOverride;
        boolean capable =
                override != null
                        ? override
                        : PersistentVboSupport.contextSupportsPersistentMapping();
        if (!capable) {
            state = VANILLA;
            info("Sprite ring keeps vanilla sizing (no persistent buffer mapping)");
            return;
        }
        requestedBuffers = System.getProperty(BUFFERS_PROPERTY);
        appliedBufferSize = wanted;
        state = SIZE_ENLARGED;
    }

    private static long parse(String raw, long fallback, String property) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            warn("Ignoring -D" + property + "=" + raw + "; using " + fallback, null);
            return fallback;
        }
    }

    private static void info(String message, Object... args) {
        try {
            StormLogger.LOGGER.info(message, args);
        } catch (Throwable ignored) {
            // Sizing must not depend on the logger.
        }
    }

    private static void warn(String message, Throwable cause) {
        try {
            if (cause == null) {
                StormLogger.LOGGER.warn(message);
            } else {
                StormLogger.LOGGER.warn(message, cause);
            }
        } catch (Throwable ignored) {
            // Sizing must not depend on the logger.
        }
    }
}
