package io.pzstorm.storm.advice.persistentvbo;

import net.bytebuddy.asm.Advice;

/**
 * Exit advice for {@code SpriteRenderer.postRender()}, the render-thread method inside which every
 * draw of a frame is issued. Fences the frame so persistent buffer slots written in it can be
 * reused once the GPU is done. {@code endFrame()} never throws; {@code suppress} is belt and
 * braces.
 */
public class SpriteRendererPostRenderAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit() {
        PersistentVboSupport.endFrame();
    }
}
