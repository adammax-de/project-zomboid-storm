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
 * Client-only. Guards the null room dereference that PZ 42.21.0 added with {@code
 * IsoTree.isPlayerInsideARoom(IsoPlayer)}:
 *
 * <pre>NullPointerException: Cannot invoke "zombie.iso.areas.IsoRoom.getRectsBounds()"
 *   because the return value of "zombie.iso.IsoGridSquare.getRoom()" is null
 *   at IsoTree.isPlayerInsideARoom ... IsoTree.render
 *   at FBORenderCell.renderTranslucentObjects ... renderTilesInternal ... renderInternal</pre>
 *
 * <p>The method tests {@code player.isInARoom()} and then reads {@code
 * player.getSquare().getRoom().getRectsBounds()}. {@code IsoGridSquare.isInARoom()} is also true
 * for a square whose world region is a player-built room ({@code IsoWorldRegion.isPlayerRoom()}),
 * and such a square can have no {@code IsoRoom}. The call sits behind the "XL" sprite test in
 * {@code IsoTree.render}, so it throws once per frame while the player stands in such a room with
 * an extra-large tree in view. {@code FBORenderCell.renderInternal} catches the throw, which drops
 * the rest of that frame's translucent pass: the error counter climbs every frame and the room's
 * furniture goes invisible until the player leaves.
 *
 * <p>The advice skips the vanilla body when the player's square has no {@code IsoRoom}, so the
 * method returns {@code false} and the tree draws opaque, as it did before 42.21.0. See {@code
 * TreePlayerRoomGuard} for the logging.
 *
 * <p>Why a client bytecode patch: the throw is inside a private Java method on the client's render
 * path, with no Lua event and no server-observable state, so no cheaper tier reaches it. Fail-soft:
 * {@code suppress = Throwable.class} resolves any advice failure to "run vanilla", and a missing
 * target method fails the transform loudly at weave time (logged, class left vanilla). Re-validate
 * the method on each game update.
 */
public class IsoTreePlayerRoomNullGuardPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.objects.IsoTree";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.client.treeroomguard.IsoTreePlayerRoomGuardAdvice";

    public IsoTreePlayerRoomNullGuardPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        ElementMatcher.Junction<MethodDescription> guarded =
                ElementMatchers.named("isPlayerInsideARoom")
                        .and(ElementMatchers.takesArguments(1))
                        .and(ElementMatchers.returns(boolean.class));
        if (target.getDeclaredMethods().filter(guarded).isEmpty()) {
            throw new IllegalStateException(
                    "IsoTreePlayerRoomNullGuardPatch: IsoTree no longer declares a one-arg boolean"
                            + " isPlayerInsideARoom — the guard would silently no-op and"
                            + " reintroduce the per-frame render NPE in player-built rooms."
                            + " Re-verify the patch against the current game source.");
        }
        return builder.visit(Advice.to(typePool.describe(ADVICE).resolve(), locator).on(guarded));
    }
}
