package io.pzstorm.storm.advice.persistentvbo;

import org.lwjgl.opengl.GL32;

/**
 * {@link SyncGl} over LWJGL's {@code GL32} entry points. The function pointers behind them are
 * shared with {@code ARBSync}, so they work on any context where {@link
 * PersistentVboSupport#endFrame()} confirmed either {@code OpenGL32} or {@code GL_ARB_sync}.
 */
public final class LwjglSyncGl implements SyncGl {

    @Override
    public long fenceSync() {
        return GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    @Override
    public int clientWaitSync(long fence, long timeoutNanos) {
        return GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, timeoutNanos);
    }

    @Override
    public void deleteSync(long fence) {
        GL32.glDeleteSync(fence);
    }
}
