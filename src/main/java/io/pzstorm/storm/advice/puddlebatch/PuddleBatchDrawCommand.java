package io.pzstorm.storm.advice.puddlebatch;

import java.util.Arrays;
import zombie.core.textures.TextureDraw;

/**
 * One puddle draw for one player and one level, handed to the render thread through {@code
 * SpriteRenderer.drawGeneric}. It carries the camera jiggle of the frame and, per batch, the square
 * count and data version the game thread expects to be drawn, plus a snapshot of the data when the
 * batch needs an upload.
 *
 * <p>The sprite renderer calls {@link #render()} with no try/catch around it, so {@link
 * PuddleBatchDraw#render} never lets a throwable out.
 */
public final class PuddleBatchDrawCommand extends TextureDraw.GenericDrawer {

    int playerIndex;
    int z;
    float jx;
    float jy;
    int size;
    PuddleBatch[] batches = new PuddleBatch[64];
    int[] counts = new int[64];
    int[] versions = new int[64];
    float[][] snapshots = new float[64][];

    PuddleBatchDrawCommand init(int playerIndex, int z, float jx, float jy) {
        this.playerIndex = playerIndex;
        this.z = z;
        this.jx = jx;
        this.jy = jy;
        this.size = 0;
        return this;
    }

    void add(PuddleBatch batch, int count, int version, float[] snapshot) {
        if (size == batches.length) {
            int capacity = size * 2;
            batches = Arrays.copyOf(batches, capacity);
            counts = Arrays.copyOf(counts, capacity);
            versions = Arrays.copyOf(versions, capacity);
            snapshots = Arrays.copyOf(snapshots, capacity);
        }
        batches[size] = batch;
        counts[size] = count;
        versions[size] = version;
        snapshots[size] = snapshot;
        size++;
    }

    /** Returns the snapshots and the command to their pools. */
    void release() {
        for (int i = 0; i < size; i++) {
            float[] snapshot = snapshots[i];
            if (snapshot != null) {
                PuddleBatchPools.releaseSnapshot(snapshot);
                snapshots[i] = null;
            }
            batches[i] = null;
        }
        size = 0;
        PuddleBatchPools.releaseCommand(this);
    }

    @Override
    public void render() {
        PuddleBatchDraw.render(this);
    }

    @Override
    public void postRender() {
        release();
    }
}
