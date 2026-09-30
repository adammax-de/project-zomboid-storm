package io.pzstorm.storm.advice.persistentvbo;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code GLVertexBufferObject.doDestroy()}. Deletes every slot of a persistent instance,
 * drops its state and leaves the fields as vanilla would ({@code id = 0}, unmapped). Vanilla's
 * {@code unmap()} + single {@code glDeleteBuffers(id)} is skipped because {@code id} names only the
 * active slot.
 */
public class GLVertexBufferObjectDestroyAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(
            @Advice.FieldValue(value = "storm$persistentVbo", readOnly = false) Object state,
            @Advice.FieldValue(value = "id", readOnly = false) int id,
            @Advice.FieldValue(value = "mapped", readOnly = false) boolean mapped) {
        if (state == null) {
            return false;
        }
        PersistentVboSupport.destroy(state);
        state = null;
        id = 0;
        mapped = false;
        return true;
    }
}
