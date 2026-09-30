package io.pzstorm.storm.advice.cutawayinvalidate;

import net.bytebuddy.asm.Advice;

/**
 * Samples the player's room and level once per frame from {@code
 * FBORenderCutaways.checkPlayerRoom}.
 */
public class CutawayViewpointAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) int playerIndex) {
        CutawayInvalidationFilter.onViewpointCheck(playerIndex);
    }
}
