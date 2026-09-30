package io.pzstorm.storm.advice.puddlebatch;

import java.util.ArrayDeque;

/**
 * Pools for draw commands and upload snapshots. The game thread allocates; whichever thread runs
 * {@code postRender()} releases, so both pools lock.
 */
final class PuddleBatchPools {

    static final int SNAPSHOT_FLOATS = PuddleBatch.MAX_SQUARES * PuddleBatch.FLOATS_PER_SQUARE;

    private static final int MAX_SNAPSHOTS = 256;
    private static final int MAX_COMMANDS = 64;

    private static final ArrayDeque<float[]> SNAPSHOTS = new ArrayDeque<>();
    private static final ArrayDeque<PuddleBatchDrawCommand> COMMANDS = new ArrayDeque<>();

    private PuddleBatchPools() {}

    static float[] allocSnapshot() {
        synchronized (SNAPSHOTS) {
            float[] snapshot = SNAPSHOTS.pollFirst();
            if (snapshot != null) {
                return snapshot;
            }
        }
        return new float[SNAPSHOT_FLOATS];
    }

    static void releaseSnapshot(float[] snapshot) {
        synchronized (SNAPSHOTS) {
            if (SNAPSHOTS.size() < MAX_SNAPSHOTS) {
                SNAPSHOTS.addFirst(snapshot);
            }
        }
    }

    static PuddleBatchDrawCommand allocCommand() {
        synchronized (COMMANDS) {
            PuddleBatchDrawCommand command = COMMANDS.pollFirst();
            if (command != null) {
                return command;
            }
        }
        return new PuddleBatchDrawCommand();
    }

    static void releaseCommand(PuddleBatchDrawCommand command) {
        synchronized (COMMANDS) {
            if (COMMANDS.size() < MAX_COMMANDS) {
                COMMANDS.addFirst(command);
            }
        }
    }
}
