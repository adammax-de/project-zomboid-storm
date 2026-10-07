package io.pzstorm.storm.advice.puddleearlyz;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.iso.IsoPuddles.renderSome(int, int, boolean)}. Runs the vanilla puddle
 * draw with depth clamp on while a rewritten puddle program is live, and turns it off afterwards
 * even when the draw throws.
 */
public class PuddleDepthClampAdvice {

    @Advice.OnMethodEnter
    public static boolean onEnter() {
        return PuddleEarlyZ.beginClamp();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter boolean clamped) {
        if (clamped) {
            PuddleEarlyZ.endClamp();
        }
    }
}
