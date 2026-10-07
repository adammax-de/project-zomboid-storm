package io.pzstorm.storm.advice.client.treeroomguard;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code IsoTree.isPlayerInsideARoom(IsoPlayer)} that skips the vanilla body when the
 * player's square has no {@code IsoRoom}. A skipped body returns {@code false}, the method's own
 * answer for a player outside any room. The player is typed {@code Object} so the inlined bytecode
 * adds no type reference to the transform target. {@code suppress} resolves any advice failure to
 * "run vanilla".
 */
public class IsoTreePlayerRoomGuardAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(@Advice.Argument(0) Object player) {
        return TreePlayerRoomGuard.hasNoRoom(player);
    }
}
