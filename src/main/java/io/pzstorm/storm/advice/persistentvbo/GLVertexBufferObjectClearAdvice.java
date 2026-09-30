package io.pzstorm.storm.advice.persistentvbo;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code GLVertexBufferObject.clear()}. Immutable storage rejects {@code glBufferData},
 * so on a persistent instance the vanilla orphan is skipped; {@code cleared} stays as it was, and
 * {@code map()} resets it.
 */
public class GLVertexBufferObjectClearAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(@Advice.FieldValue("storm$persistentVbo") Object state) {
        return PersistentVboSupport.isPersistent(state);
    }
}
