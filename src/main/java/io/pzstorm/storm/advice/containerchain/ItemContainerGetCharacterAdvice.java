package io.pzstorm.storm.advice.containerchain;

import io.pzstorm.storm.patch.fixes.ContainerChainGuard;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Replaces the body of {@code ItemContainer.getCharacter()} with the bounded walk in {@link
 * ContainerChainGuard#getCharacter(Object)}. If the helper throws, the vanilla body runs.
 */
public class ItemContainerGetCharacterAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object onEnter(@Advice.This Object container) {
        try {
            return ContainerChainGuard.getCharacter(container);
        } catch (Throwable t) {
            return null;
        }
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter Object computed,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned) {
        if (computed != null) {
            returned = computed == ContainerChainGuard.NONE ? null : computed;
        }
    }
}
