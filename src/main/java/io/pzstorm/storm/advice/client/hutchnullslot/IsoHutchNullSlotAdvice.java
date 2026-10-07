package io.pzstorm.storm.advice.client.hutchnullslot;

import java.util.Map;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code IsoHutch.removeFromWorld()} and {@code releaseAllAnimals()} that strips null
 * animal slots before the vanilla body iterates them.
 *
 * <p>The null test is inlined so the common case costs one {@code containsValue} over a handful of
 * slots; the removal lives in {@link HutchNullSlotGuard}, which only loads the first time a coop
 * actually carries a null. {@code animalInside} is typed {@code Map} and {@code @Advice.This} is
 * not narrowed to the transform target, so the inlined bytecode never references a type that is
 * still being defined. {@code suppress} resolves any advice failure to "run vanilla".
 */
public class IsoHutchNullSlotAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This Object self, @Advice.FieldValue("animalInside") Map<?, ?> animalInside) {
        if (animalInside != null && animalInside.containsValue(null)) {
            HutchNullSlotGuard.stripNullSlots(self, animalInside);
        }
    }
}
