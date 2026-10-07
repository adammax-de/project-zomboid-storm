package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Guards the null animal dereference that PZ 42.21.0 added to {@code
 * IsoHutch.removeFromWorld()}:
 *
 * <pre>
 * NullPointerException: Cannot invoke "zombie.characters.animals.IsoAnimal.removeFromUpdateLists()"
 *   because "animal" is null at IsoHutch.removeFromWorld
 *   at IsoObject.removeFromWorldToMeta ... IsoChunk.removeFromWorld ... IsoChunkMap.LoadUp
 *   at IsoChunkMap.ProcessChunkPos ... IngameState.updateInternal</pre>
 *
 * <p>42.21.0 made {@code removeFromWorld()} call {@code removeFromUpdateLists()} on every value of
 * {@code animalInside}. Vanilla client code stores null placeholders in that same map on purpose:
 * {@code NetworkPlayerAI} nulls a coop animal's old slot on every slot move, and {@code
 * AnimalUpdatePacket.processClient} nulls the server-named slot before {@code addAnimalInside},
 * which leaves the null behind whenever it rerolls the slot or refuses a duplicate. When the coop's
 * chunk scrolls out of the client's loaded area the NPE escapes the world tick and {@code
 * IngameState} sends the player to the main menu ({@code force-disconnect "crash"}). {@code
 * releaseAllAnimals()} iterates the same values into {@code releaseAnimal(null, animal)} with the
 * same hole.
 *
 * <p>The advice strips null values from {@code animalInside} on entry to both methods. A null slot
 * carries no meaning anywhere in vanilla (every reader treats {@code get(pos) == null} as empty),
 * so removing it changes nothing but the crash. See {@code HutchNullSlotGuard} for the logging.
 *
 * <p>Why a client bytecode patch: the throw is inside the client's chunk-unload path, a Java call
 * chain with no Lua event and no server-observable state, and the nulls are written by client-only
 * packet handlers, so no server, Lua or event-bridge tier reaches it. Fail-soft: {@code suppress =
 * Throwable.class} resolves any advice failure to "run vanilla", and a missing target method or
 * field fails the transform loudly at weave time (logged, class left vanilla). Re-validate both
 * methods and the {@code animalInside} field on each game update.
 */
public class IsoHutchNullAnimalSlotGuardPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.objects.IsoHutch";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.client.hutchnullslot.IsoHutchNullSlotAdvice";

    public IsoHutchNullAnimalSlotGuardPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        ElementMatcher.Junction<MethodDescription> guarded =
                ElementMatchers.namedOneOf("removeFromWorld", "releaseAllAnimals")
                        .and(ElementMatchers.takesArguments(0));
        if (target.getDeclaredMethods().filter(guarded).size() != 2
                || target.getDeclaredFields()
                        .filter(ElementMatchers.named("animalInside"))
                        .isEmpty()) {
            throw new IllegalStateException(
                    "IsoHutchNullAnimalSlotGuardPatch: IsoHutch no longer declares no-arg"
                            + " removeFromWorld() and releaseAllAnimals() plus the animalInside"
                            + " field — the guard would silently no-op and reintroduce the null-slot"
                            + " NPE that disconnects the client. Re-verify the patch against the"
                            + " current game source.");
        }
        return builder.visit(Advice.to(typePool.describe(ADVICE).resolve(), locator).on(guarded));
    }
}
