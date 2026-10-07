package io.pzstorm.storm.advice.cutawayinvalidate;

import net.bytebuddy.asm.Advice;

/**
 * Brackets {@code FBORenderCutaways.doCutawayVisitSquares(int, ArrayList)} so the invalidations
 * recorded during the call are settled on every exit path. Free of game types, so inlining it never
 * forces a class load.
 */
public class CutawayVisitSquaresAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This Object self, @Advice.Argument(0) int playerIndex) {
        CutawayInvalidationFilter.begin(self, playerIndex);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Thrown Throwable thrown) {
        CutawayInvalidationFilter.end(thrown != null);
    }
}
