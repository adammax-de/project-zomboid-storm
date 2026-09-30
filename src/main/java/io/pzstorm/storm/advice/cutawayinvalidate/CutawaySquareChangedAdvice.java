package io.pzstorm.storm.advice.cutawayinvalidate;

import net.bytebuddy.asm.Advice;

/**
 * Forwards {@code FBORenderCutaways.squareChanged}, which the lighting pass calls on a could-see
 * flip.
 */
public class CutawaySquareChangedAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Object square) {
        CutawayInvalidationFilter.onSquareChanged(square);
    }
}
