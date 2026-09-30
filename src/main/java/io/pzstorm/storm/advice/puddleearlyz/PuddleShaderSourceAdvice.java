package io.pzstorm.storm.advice.puddleearlyz;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.core.opengl.ShaderUnit.loadShaderFile(String, ArrayList)}. Hands the
 * loaded shader text to {@link PuddleEarlyZ}, which replaces it for the two shared puddle units and
 * returns every other text as is.
 */
public class PuddleShaderSourceAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Argument(0) String fileName, @Advice.Return(readOnly = false) String source) {
        source = PuddleEarlyZ.onShaderSource(fileName, source);
    }
}
