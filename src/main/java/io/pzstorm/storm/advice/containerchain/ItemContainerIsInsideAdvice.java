package io.pzstorm.storm.advice.containerchain;

import io.pzstorm.storm.patch.fixes.ContainerChainGuard;
import net.bytebuddy.asm.Advice;

/**
 * Replaces the body of {@code ItemContainer.isInside(InventoryItem)} with the bounded walk in
 * {@link ContainerChainGuard#isInside(Object, Object)}. The enter value is 0 when the helper threw
 * (vanilla body runs), 1 for {@code false}, 2 for {@code true}.
 */
public class ItemContainerIsInsideAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(@Advice.This Object container, @Advice.Argument(0) Object item) {
        try {
            return ContainerChainGuard.isInside(container, item);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int computed, @Advice.Return(readOnly = false) boolean returned) {
        if (computed != 0) {
            returned = computed == 2;
        }
    }
}
