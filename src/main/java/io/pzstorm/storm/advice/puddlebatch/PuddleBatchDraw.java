package io.pzstorm.storm.advice.puddlebatch;

import io.pzstorm.storm.advice.puddleearlyz.PuddleEarlyZ;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import zombie.core.Core;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.Shader;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.textures.Texture;
import zombie.iso.IsoPuddles;
import zombie.iso.PuddlesShader;

/**
 * Render-thread half of the puddle batch cache. Draws one {@link PuddleBatchDrawCommand}: the GL
 * state of {@code ModelManager.RenderPuddles} and {@code IsoPuddles.renderSome}, with each batch
 * drawn from its own buffer instead of from a copy into the shared puddle buffer.
 *
 * <p>The camera jiggle of the frame is the model-view translation, so the stored vertex positions
 * carry none. A batch is uploaded only when its command item carries a snapshot.
 *
 * <p>Nothing here throws. A throwable restores the GL state, latches the batch path off and leaves
 * the rest of the frame to draw without puddles; the next game-thread pass runs vanilla.
 */
final class PuddleBatchDraw {

    private static final int GL_ARRAY_BUFFER = 34962;
    private static final int GL_ELEMENT_ARRAY_BUFFER = 34963;
    private static final int GL_STATIC_DRAW = 35044;
    private static final int GL_DYNAMIC_DRAW = 35048;
    private static final int GL_FLOAT = 5126;
    private static final int GL_UNSIGNED_BYTE = 5121;
    private static final int GL_UNSIGNED_SHORT = 5123;
    private static final int GL_TRIANGLES = 4;
    private static final int STRIDE = PuddleBatch.FLOATS_PER_VERTEX * 4;

    private static int indexBuffer;
    private static FloatBuffer uploadBuffer;

    private PuddleBatchDraw() {}

    static void render(PuddleBatchDrawCommand command) {
        try {
            deleteRetired();
            if (command.size == 0 || PuddleBatchRenderer.hasFailed()) {
                return;
            }
            draw(command);
        } catch (Throwable t) {
            PuddleBatchRenderer.fail("render-thread draw", t);
        }
    }

    private static void deleteRetired() {
        int deleted = 0;
        PuddleBatch batch;
        while ((batch = PuddleBatchRenderer.RETIRED.poll()) != null) {
            if (batch.vbo != 0) {
                GL15.glDeleteBuffers(batch.vbo);
                batch.vbo = 0;
                deleted++;
            }
        }
        if (deleted != 0) {
            PuddleBatchStats.buffersDeleted += deleted;
        }
    }

    private static void draw(PuddleBatchDrawCommand command) {
        Core core = Core.getInstance();
        boolean projectionPushed = false;
        boolean modelViewPushed = false;
        boolean clamped = false;
        int uploads = 0;
        int draws = 0;
        int staleSkips = 0;
        int created = 0;

        GL11.glPushClientAttrib(-1);
        GL11.glPushAttrib(1048575);
        try {
            Matrix4f projection = core.projectionMatrixStack.alloc();
            IsoPuddles.getInstance().puddlesProjection(projection);
            core.projectionMatrixStack.push(projection);
            projectionPushed = true;

            Matrix4f modelView = core.modelViewMatrixStack.alloc();
            modelView.translation(command.jx, command.jy, 0.0F);
            core.modelViewMatrixStack.push(modelView);
            modelViewPushed = true;

            Shader shader = IsoPuddles.getInstance().effect;
            ShaderHelper.glUseProgramObjectARB(shader.getID());
            if (shader instanceof PuddlesShader) {
                ((PuddlesShader) shader).updatePuddlesParams(command.playerIndex, command.z);
            }
            VertexBufferObject.setModelViewProjection(shader.getProgram());

            bindIndexBuffer();
            for (int attrib = 0; attrib <= 6; attrib++) {
                GL20.glEnableVertexAttribArray(attrib);
            }
            GL11.glDepthMask(false);
            GL11.glBlendFunc(770, 771);
            GL11.glEnable(2929);
            GL11.glDepthFunc(515);
            clamped = PuddleEarlyZ.beginClamp();

            for (int i = 0; i < command.size; i++) {
                PuddleBatch batch = command.batches[i];
                if (batch.dropped) {
                    continue;
                }
                int count = command.counts[i];
                int version = command.versions[i];
                float[] snapshot = command.snapshots[i];
                if (batch.vbo == 0) {
                    batch.vbo = GL15.glGenBuffers();
                    created++;
                }
                GL15.glBindBuffer(GL_ARRAY_BUFFER, batch.vbo);
                if (snapshot != null) {
                    FloatBuffer upload = uploadBuffer();
                    upload.clear();
                    upload.put(snapshot, 0, count * PuddleBatch.FLOATS_PER_SQUARE);
                    upload.flip();
                    GL15.glBufferData(GL_ARRAY_BUFFER, upload, GL_DYNAMIC_DRAW);
                    batch.vboVersion = version;
                    batch.uploadedVersion = version;
                    uploads++;
                }
                if (batch.vboVersion != version) {
                    staleSkips++;
                    continue;
                }
                GL20.glVertexAttribPointer(2, 1, GL_FLOAT, true, STRIDE, 0L);
                GL20.glVertexAttribPointer(3, 1, GL_FLOAT, true, STRIDE, 4L);
                GL20.glVertexAttribPointer(4, 1, GL_FLOAT, true, STRIDE, 8L);
                GL20.glVertexAttribPointer(5, 1, GL_FLOAT, true, STRIDE, 12L);
                GL20.glVertexAttribPointer(0, 2, GL_FLOAT, false, STRIDE, 16L);
                GL20.glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, STRIDE, 24L);
                GL20.glVertexAttribPointer(6, 1, GL_FLOAT, true, STRIDE, 28L);
                GL12.glDrawRangeElements(
                        GL_TRIANGLES, 0, count * 4 - 1, count * 6, GL_UNSIGNED_SHORT, 0L);
                draws++;
            }
        } finally {
            if (clamped) {
                PuddleEarlyZ.endClamp();
            }
            ShaderHelper.glUseProgramObjectARB(0);
            if (projectionPushed) {
                core.projectionMatrixStack.pop();
            }
            if (modelViewPushed) {
                core.modelViewMatrixStack.pop();
            }
            GL11.glPopAttrib();
            GL11.glPopClientAttrib();
            Texture.lastTextureID = -1;
            SpriteRenderer.ringBuffer.restoreVbos = true;
            GLStateRenderThread.restore();

            PuddleBatchStats.commands++;
            PuddleBatchStats.uploads += uploads;
            PuddleBatchStats.draws += draws;
            PuddleBatchStats.staleSkips += staleSkips;
            PuddleBatchStats.buffersCreated += created;
        }
    }

    /** Two triangles per quad in the vertex order of {@code IsoPuddles.renderSome}. */
    private static void bindIndexBuffer() {
        if (indexBuffer != 0) {
            GL15.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            return;
        }
        ShortBuffer indices =
                ByteBuffer.allocateDirect(PuddleBatch.MAX_SQUARES * 6 * 2)
                        .order(ByteOrder.nativeOrder())
                        .asShortBuffer();
        for (int vertex = 0; vertex < PuddleBatch.MAX_SQUARES * 4; vertex += 4) {
            indices.put((short) vertex);
            indices.put((short) (vertex + 1));
            indices.put((short) (vertex + 2));
            indices.put((short) vertex);
            indices.put((short) (vertex + 2));
            indices.put((short) (vertex + 3));
        }
        indices.flip();
        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        indexBuffer = buffer;
    }

    private static FloatBuffer uploadBuffer() {
        if (uploadBuffer == null) {
            uploadBuffer =
                    ByteBuffer.allocateDirect(PuddleBatchPools.SNAPSHOT_FLOATS * 4)
                            .order(ByteOrder.nativeOrder())
                            .asFloatBuffer();
        }
        return uploadBuffer;
    }
}
