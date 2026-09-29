package io.pzstorm.storm.advice.containerchain;

import io.pzstorm.storm.patch.fixes.ContainerChainGuard;
import net.bytebuddy.asm.Advice;

/**
 * Gates {@code ItemContainer.AddItem(InventoryItem)}: when the add would place a bag inside its own
 * contents, the vanilla body is skipped and the method returns {@code null}, the same value vanilla
 * returns for an item it cannot add. If the helper throws, the vanilla body runs.
 */
public class ItemContainerAddItemAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object container, @Advice.Argument(0) Object item) {
        try {
            return ContainerChainGuard.wouldNest(container, item);
        } catch (Throwable t) {
            return false;
        }
    }
}
