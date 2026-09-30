package io.pzstorm.storm.advice.persistentvbo;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code GLVertexBufferObject.unmap()}. A persistent slot is never unmapped; the advice
 * stamps it with the current frame so the next {@code map()} of that slot can wait for the GPU,
 * clears {@code mapped} and reports success without touching GL.
 */
public class GLVertexBufferObjectUnmapAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(
            @Advice.FieldValue("storm$persistentVbo") Object state,
            @Advice.FieldValue(value = "mapped", readOnly = false) boolean mapped) {
        if (state == null) {
            return false;
        }
        if (mapped) {
            PersistentVboSupport.release(state);
            mapped = false;
        }
        return true;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter boolean persistent, @Advice.Return(readOnly = false) boolean result) {
        if (persistent) {
            result = true;
        }
    }
}
