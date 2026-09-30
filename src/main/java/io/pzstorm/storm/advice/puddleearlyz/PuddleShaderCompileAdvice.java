package io.pzstorm.storm.advice.puddleearlyz;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.core.opengl.ShaderProgram.compile()}. Brackets each compile so {@link
 * PuddleEarlyZ} can tie a shader rewrite to the program it went into, and compile a puddle program
 * again as vanilla when the rewritten one failed.
 */
public class PuddleShaderCompileAdvice {

    @Advice.OnMethodEnter
    public static int onEnter() {
        return PuddleEarlyZ.onCompileEnter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.This Object self, @Advice.Enter int serial, @Advice.Thrown Throwable thrown) {
        PuddleEarlyZ.onCompileExit(self, serial, thrown);
    }
}
