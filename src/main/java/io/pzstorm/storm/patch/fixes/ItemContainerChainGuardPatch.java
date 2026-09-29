package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Makes every walk up the {@code ItemContainer} chain cycle-safe and refuses the {@code AddItem}
 * that would create a cycle. See {@link ContainerChainGuard} for the mechanism.
 *
 * <p>Vanilla {@code getCharacter()}, {@code getOutermostContainer()}, {@code isInside()} and {@code
 * isInCharacterInventory()} recurse through {@code containingItem.getContainer()} without bound; a
 * bag inside its own contents turns each into a {@code StackOverflowError} on the main thread
 * ({@code Remove()} calls {@code getCharacter()}, so the next pickup from that container crashes
 * the server). Registered on both JVMs: the client runs the same walks on its mirror of the
 * container.
 */
public class ItemContainerChainGuardPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.containerchain.";

    public ItemContainerChainGuardPatch() {
        super("zombie.inventory.ItemContainer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "ItemContainerGetCharacterAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("getCharacter")
                                                .and(ElementMatchers.takesArguments(0))))
                .visit(
                        Advice.to(
                                        typePool.describe(
                                                        PKG
                                                                + "ItemContainerGetOutermostContainerAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("getOutermostContainer")
                                                .and(ElementMatchers.takesArguments(0))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "ItemContainerIsInsideAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("isInside")
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(
                                        typePool.describe(
                                                        PKG
                                                                + "ItemContainerIsInCharacterInventoryAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("isInCharacterInventory")
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "ItemContainerAddItemAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("AddItem")
                                                .and(ElementMatchers.takesArguments(1))
                                                .and(
                                                        ElementMatchers.takesArgument(
                                                                0,
                                                                ElementMatchers.named(
                                                                        "zombie.inventory.InventoryItem")))));
    }
}
