package io.pzstorm.storm.advice.persistentvbo;

import java.nio.ByteBuffer;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code GLVertexBufferObject.map()}. On an unadopted instance it asks {@link
 * PersistentVboSupport#adopt} once; on a persistent instance it rotates to the next slot, points
 * {@code id} and {@code buffer} at it and skips the vanilla orphan-and-map body. An already-mapped
 * persistent instance returns its buffer, as vanilla does. {@code suppress} resolves any advice
 * failure to "run vanilla".
 */
public class GLVertexBufferObjectMapAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(
            @Advice.FieldValue(value = "storm$persistentVbo", readOnly = false) Object state,
            @Advice.FieldValue("size") long size,
            @Advice.FieldValue("type") int type,
            @Advice.FieldValue(value = "id", readOnly = false) int id,
            @Advice.FieldValue(value = "mapped", readOnly = false) boolean mapped,
            @Advice.FieldValue(value = "cleared", readOnly = false) boolean cleared,
            @Advice.FieldValue(value = "buffer", readOnly = false) ByteBuffer buffer) {
        if (state == null) {
            if (mapped) {
                return false;
            }
            state = PersistentVboSupport.adopt(id, size, type);
            if (state == null) {
                return false;
            }
        }
        if (!mapped) {
            buffer = PersistentVboSupport.acquire(state);
            id = PersistentVboSupport.activeName(state);
            mapped = true;
            cleared = false;
        }
        return true;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter boolean persistent,
            @Advice.FieldValue("buffer") ByteBuffer buffer,
            @Advice.Return(readOnly = false) ByteBuffer result) {
        if (persistent) {
            result = buffer;
        }
    }
}
