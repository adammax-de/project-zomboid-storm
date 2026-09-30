package io.pzstorm.storm.advice.puddlebatch;

import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Side table of puddle batches, keyed by chunk identity and then by player and level. The game
 * thread creates, sweeps and drops entries. Hooks on any thread only look up a batch and set a flag
 * on it, so every structure here is safe for concurrent reads.
 *
 * <p>A chunk object is pooled by the game and reused for another position, so its entry outlives
 * one position. The sweep removes entries that no pass has touched for a long time.
 */
public final class PuddleBatchTable {

    static final int PLAYERS = 4;
    static final int LEVELS = 64;
    static final int LEVEL_OFFSET = 32;

    private final ConcurrentHashMap<Object, ChunkEntry> chunks = new ConcurrentHashMap<>();

    static final class ChunkEntry {
        final AtomicReferenceArray<PuddleBatch> slots =
                new AtomicReferenceArray<>(PLAYERS * LEVELS);
        volatile int lastSeenFrame;
    }

    private static int slot(int playerIndex, int z) {
        if (playerIndex < 0 || playerIndex >= PLAYERS) {
            return -1;
        }
        int level = z + LEVEL_OFFSET;
        if (level < 0 || level >= LEVELS) {
            return -1;
        }
        return playerIndex * LEVELS + level;
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public int chunkCount() {
        return chunks.size();
    }

    public PuddleBatch get(Object chunk, int playerIndex, int z) {
        int slot = slot(playerIndex, z);
        if (slot < 0) {
            return null;
        }
        ChunkEntry entry = chunks.get(chunk);
        return entry == null ? null : entry.slots.get(slot);
    }

    /** Game thread only. Returns null when the player or level is out of range. */
    public PuddleBatch getOrCreate(Object chunk, int playerIndex, int z, int frame) {
        int slot = slot(playerIndex, z);
        if (slot < 0) {
            return null;
        }
        ChunkEntry entry = chunks.get(chunk);
        if (entry == null) {
            entry = new ChunkEntry();
            chunks.put(chunk, entry);
        }
        entry.lastSeenFrame = frame;
        PuddleBatch batch = entry.slots.get(slot);
        if (batch == null) {
            batch = new PuddleBatch();
            entry.slots.set(slot, batch);
        }
        return batch;
    }

    public void markInvalid(Object chunk, int playerIndex, int z) {
        PuddleBatch batch = get(chunk, playerIndex, z);
        if (batch != null && !batch.invalid) {
            batch.invalid = true;
        }
    }

    public void markLightsDirty(Object chunk, int playerIndex, int z) {
        PuddleBatch batch = get(chunk, playerIndex, z);
        if (batch != null && !batch.lightsDirty) {
            batch.lightsDirty = true;
        }
    }

    public void markChunkInvalid(Object chunk) {
        ChunkEntry entry = chunks.get(chunk);
        if (entry != null) {
            invalidate(entry);
        }
    }

    public void invalidateAll() {
        for (ChunkEntry entry : chunks.values()) {
            invalidate(entry);
        }
    }

    private static void invalidate(ChunkEntry entry) {
        for (int i = 0; i < entry.slots.length(); i++) {
            PuddleBatch batch = entry.slots.get(i);
            if (batch != null) {
                batch.invalid = true;
            }
        }
    }

    /** Game thread only. Drops every entry and hands its batches to {@code retired}. */
    public int dropAll(Queue<PuddleBatch> retired) {
        int dropped = 0;
        Iterator<Map.Entry<Object, ChunkEntry>> it = chunks.entrySet().iterator();
        while (it.hasNext()) {
            dropped += retire(it.next().getValue(), retired);
            it.remove();
        }
        return dropped;
    }

    /**
     * Game thread only. Drops entries that no pass has touched for more than {@code maxAge} frames,
     * or whose last-seen frame lies in the future of {@code frame}.
     */
    public int sweep(int frame, int maxAge, Queue<PuddleBatch> retired) {
        int dropped = 0;
        Iterator<Map.Entry<Object, ChunkEntry>> it = chunks.entrySet().iterator();
        while (it.hasNext()) {
            ChunkEntry entry = it.next().getValue();
            int age = frame - entry.lastSeenFrame;
            if (age > maxAge || age < 0) {
                dropped += retire(entry, retired);
                it.remove();
            }
        }
        return dropped;
    }

    private static int retire(ChunkEntry entry, Queue<PuddleBatch> retired) {
        int dropped = 0;
        for (int i = 0; i < entry.slots.length(); i++) {
            PuddleBatch batch = entry.slots.get(i);
            if (batch != null) {
                batch.drop();
                retired.add(batch);
                dropped++;
            }
        }
        return dropped;
    }
}
