package io.pzstorm.storm.advice.puddlebatch;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.iso.fboRenderChunk.FBORenderCell.renderPuddles(int)}.
 *
 * <p>Routes the call through {@link PuddleBatchRenderer#render}. A {@code true} verdict means the
 * batch pass handled the frame and the vanilla body is skipped. A {@code false} verdict leaves the
 * vanilla body to run untouched. This class stays free of game types so inlining it into the target
 * never forces a class load.
 */
public class PuddleBatchRenderAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object self, @Advice.Argument(0) int playerIndex) {
        return PuddleBatchRenderer.render(self, playerIndex);
    }
}
