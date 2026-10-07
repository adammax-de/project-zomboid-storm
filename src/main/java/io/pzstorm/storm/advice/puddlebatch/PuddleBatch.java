package io.pzstorm.storm.advice.puddlebatch;

import java.util.Arrays;

/**
 * Packed puddle vertices of one chunk, one player and one level, plus the bookkeeping that decides
 * when they are stale. Holds no game types, so the rules are unit-testable: squares are kept as
 * {@code Object} and the renderer casts them.
 *
 * <p>Threading: the game thread owns every field except the ones named below. {@link #invalid} and
 * {@link #lightsDirty} are written by hooks on any thread. {@link #vbo} and {@link #vboVersion}
 * belong to the render thread. {@link #uploadedVersion} is written by the render thread and read by
 * the game thread. {@link #dropped} is written by whichever thread drops the batch.
 */
public final class PuddleBatch {

    public static final int MAX_SQUARES = 64;
    public static final int FLOATS_PER_VERTEX = 8;
    public static final int FLOATS_PER_SQUARE = 4 * FLOATS_PER_VERTEX;

    public static final int REUSE = 0;
    public static final int REBUILD_INVALID = 1;
    public static final int REBUILD_LIST = 2;
    public static final int REBUILD_MASK = 3;
    public static final int REBUILD_EXPIRED = 4;

    public static final int CAUSE_REBUILD = 1;
    public static final int CAUSE_LIGHT = 2;
    public static final int CAUSE_SHIFT = 4;

    static final int OFFSET_X = 4;
    static final int OFFSET_Y = 5;
    static final int OFFSET_COLOR = 6;
    static final int OFFSET_DEPTH = 7;

    /** Passes to wait for the render thread to confirm an upload before sending it again. */
    static final int UPLOAD_RETRY_PASSES = 16;

    private static final float[] NO_DATA = new float[0];
    private static final Object[] NO_SQUARES = new Object[0];

    float[] data = NO_DATA;
    Object[] squares = NO_SQUARES;
    int count;
    int listSize = -1;
    long flagMask;
    int camChunkX;
    int camChunkY;
    int builtFrame;
    int expiryFrame;
    boolean dirty;
    int dirtyCauses;
    int version;
    long attachPass;

    volatile boolean invalid = true;
    volatile boolean lightsDirty;
    volatile int uploadedVersion;
    volatile boolean dropped;

    int vbo;
    int vboVersion;

    /** One bit per square of the chunk level, taken from bit 0 of its cutaway flag byte. */
    public static long flagMask(byte[] squareFlags) {
        long mask = 0L;
        int n = Math.min(squareFlags.length, 64);
        for (int i = 0; i < n; i++) {
            if ((squareFlags[i] & 1) != 0) {
                mask |= 1L << i;
            }
        }
        return mask;
    }

    /** Spreads backstop rebuilds over the interval so they never land on one frame. */
    public static int stagger(int wx, int wy, int z, int interval) {
        int hash = wx * 73856093 ^ wy * 19349663 ^ z * 83492791;
        return Math.floorMod(hash, Math.max(1, interval));
    }

    public int rebuildCause(int currentListSize, long currentMask, int frame) {
        if (invalid) {
            return REBUILD_INVALID;
        }
        if (currentListSize != listSize) {
            return REBUILD_LIST;
        }
        if (currentMask != flagMask) {
            return REBUILD_MASK;
        }
        if (frame >= expiryFrame || frame < builtFrame) {
            return REBUILD_EXPIRED;
        }
        return REUSE;
    }

    /**
     * Clears the hook flags. The build calls this before it reads any game state, so a hook that
     * fires during the build is seen by the next pass.
     */
    public void beginBuild() {
        invalid = false;
        lightsDirty = false;
        count = 0;
    }

    public void ensureCapacity(int squareCount) {
        if (squares.length < squareCount) {
            squares = Arrays.copyOf(squares, Math.min(MAX_SQUARES, Math.max(squareCount, 8)));
        }
    }

    /**
     * Copies {@code squareCount} packed squares and removes the camera jiggle from every vertex
     * position.
     */
    public void load(float[] packed, int squareCount, float jx, float jy) {
        int floats = squareCount * FLOATS_PER_SQUARE;
        if (data.length < floats) {
            data = new float[floats];
        }
        System.arraycopy(packed, 0, data, 0, floats);
        for (int n = 0; n < floats; n += FLOATS_PER_VERTEX) {
            data[n + OFFSET_X] -= jx;
            data[n + OFFSET_Y] -= jy;
        }
        count = squareCount;
    }

    public void finishBuild(
            int currentListSize,
            long currentMask,
            int camChunkX,
            int camChunkY,
            int frame,
            int interval,
            int stagger) {
        for (int i = count; i < squares.length; i++) {
            squares[i] = null;
        }
        this.listSize = currentListSize;
        this.flagMask = currentMask;
        this.camChunkX = camChunkX;
        this.camChunkY = camChunkY;
        this.builtFrame = frame;
        this.expiryFrame = frame + interval + stagger;
        markDirty(CAUSE_REBUILD);
    }

    /** A packing mismatch leaves nothing to draw and keeps the batch due for a rebuild. */
    public void failBuild() {
        count = 0;
        Arrays.fill(squares, null);
        invalid = true;
    }

    /** Returns true when the stored colour of that vertex differed and was replaced. */
    public boolean setLight(int square, int vertex, int lightBits) {
        int index = square * FLOATS_PER_SQUARE + vertex * FLOATS_PER_VERTEX + OFFSET_COLOR;
        if (Float.floatToRawIntBits(data[index]) == lightBits) {
            return false;
        }
        data[index] = Float.intBitsToFloat(lightBits);
        return true;
    }

    /** Adds one constant to every vertex depth. Returns true when the data changed. */
    public boolean shiftDepth(float delta, int newCamChunkX, int newCamChunkY) {
        camChunkX = newCamChunkX;
        camChunkY = newCamChunkY;
        if (delta == 0.0F) {
            return false;
        }
        int floats = count * FLOATS_PER_SQUARE;
        for (int n = OFFSET_DEPTH; n < floats; n += FLOATS_PER_VERTEX) {
            data[n] += delta;
        }
        return count > 0;
    }

    /**
     * Game thread only. Lets go of the squares and the data; the render thread frees the buffer.
     */
    public void drop() {
        dropped = true;
        invalid = true;
        count = 0;
        squares = NO_SQUARES;
        data = NO_DATA;
    }

    public void markDirty(int cause) {
        dirty = true;
        dirtyCauses |= cause;
        version++;
    }

    /**
     * True when this pass must attach the data for upload: it changed, or an earlier upload was
     * never confirmed by the render thread.
     */
    public boolean needsUpload(long pass) {
        if (dirty) {
            return true;
        }
        return uploadedVersion != version && pass - attachPass > UPLOAD_RETRY_PASSES;
    }

    /** Copies the data into {@code snapshot} and clears {@link #dirty}. Returns the causes. */
    public int takeSnapshot(float[] snapshot, long pass) {
        System.arraycopy(data, 0, snapshot, 0, count * FLOATS_PER_SQUARE);
        int causes = dirtyCauses;
        dirty = false;
        dirtyCauses = 0;
        attachPass = pass;
        return causes;
    }

    public int count() {
        return count;
    }

    public int version() {
        return version;
    }

    public boolean isInvalid() {
        return invalid;
    }

    public boolean isLightsDirty() {
        return lightsDirty;
    }

    public boolean isDirty() {
        return dirty;
    }

    public boolean isDropped() {
        return dropped;
    }

    public int expiryFrame() {
        return expiryFrame;
    }

    public float[] data() {
        return data;
    }
}
