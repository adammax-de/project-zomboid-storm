package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Bounds the {@code while} loop in {@code InventoryItem.getOutermostContainer()}. On a cyclic
 * container chain the vanilla loop never exits and pins the main thread at 0 TPS with no exception.
 * See {@link ContainerChainGuard}. Registered on both JVMs alongside {@link
 * ItemContainerChainGuardPatch}.
 */
public class InventoryItemGetOutermostContainerPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.containerchain.";

    public InventoryItemGetOutermostContainerPatch() {
        super("zombie.inventory.InventoryItem");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "InventoryItemGetOutermostContainerAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("getOutermostContainer")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
