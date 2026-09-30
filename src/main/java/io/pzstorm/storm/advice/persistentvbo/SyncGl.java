package io.pzstorm.storm.advice.persistentvbo;

/**
 * The three GL sync calls {@link FrameFenceRing} needs, behind an interface so the ring's
 * bookkeeping can be unit-tested against a fake. {@link LwjglSyncGl} is the real implementation.
 */
public interface SyncGl {

    /** {@code glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)}; returns the sync handle. */
    long fenceSync();

    /**
     * {@code glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, timeoutNanos)}; returns the GL
     * status enum ({@code GL_ALREADY_SIGNALED}, {@code GL_CONDITION_SATISFIED}, {@code
     * GL_TIMEOUT_EXPIRED} or {@code GL_WAIT_FAILED}).
     */
    int clientWaitSync(long fence, long timeoutNanos);

    /** {@code glDeleteSync(fence)}. */
    void deleteSync(long fence);
}
