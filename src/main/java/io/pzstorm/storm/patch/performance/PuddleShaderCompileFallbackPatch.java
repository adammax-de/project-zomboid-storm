package io.pzstorm.storm.patch.performance;

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
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}. Sub-flag
 * {@code -Dstorm.clientperf.puddles.earlyz=false} turns it off.
 *
 * <p>Compile bracket for the puddle shader rewrite of {@link PuddleShaderSourceRewritePatch}. Enter
 * and exit advice on {@code ShaderProgram.compile()} give every compile a serial, so a rewrite is
 * tied to the program it went into. When a program named {@code puddles_*} took a rewrite and ends
 * the compile without a program id, the rewrite is latched off for the session, one line is logged
 * and {@code compile()} runs again with the vanilla text.
 *
 * <p>Why a client bytecode patch: without it a driver that rejects the rewritten shader would leave
 * the player with no puddle shader. The compile is a Java method with no Lua event.
 *
 * <p>Fail-soft: the bracket changes nothing for other programs or for a compile without a rewrite.
 * A {@code Throwable} in the fallback is logged and latches the rewrite off. A compile that throws
 * keeps throwing to its caller, and the game compiles the shader again on its next use. A missing
 * target method fails the transform at weave time and leaves the class vanilla.
 *
 * <p>Re-validate on each game update: {@code ShaderProgram.compile()} still loads every unit
 * through {@code ShaderUnit.loadShaderFile} inside the call, vertex units before fragment units;
 * every failure path still ends in {@code destroy()}, so {@code isCompiled()} is false; a second
 * {@code compile()} still reloads the units from disk; and the puddle programs are still named
 * {@code puddles_hq}, {@code puddles_mq} and {@code puddles_lq}.
 */
public class PuddleShaderCompileFallbackPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.core.opengl.ShaderProgram";
    private static final String PKG = "io.pzstorm.storm.advice.puddleearlyz.";

    public PuddleShaderCompileFallbackPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        ElementMatcher.Junction<MethodDescription> compile =
                ElementMatchers.named("compile")
                        .and(ElementMatchers.takesArguments(0))
                        .and(ElementMatchers.returns(void.class));
        require(target, compile, "void compile()");
        require(
                target,
                ElementMatchers.named("isCompiled")
                        .and(ElementMatchers.takesArguments(0))
                        .and(ElementMatchers.returns(boolean.class)),
                "boolean isCompiled()");
        require(
                target,
                ElementMatchers.named("getName")
                        .and(ElementMatchers.takesArguments(0))
                        .and(ElementMatchers.returns(String.class)),
                "String getName()");
        return builder.visit(
                Advice.to(typePool.describe(PKG + "PuddleShaderCompileAdvice").resolve(), locator)
                        .on(compile));
    }

    private static void require(
            TypeDescription target,
            ElementMatcher.Junction<MethodDescription> matcher,
            String signature) {
        if (target.getDeclaredMethods().filter(matcher).isEmpty()) {
            throw new IllegalStateException(
                    "PuddleShaderCompileFallbackPatch: ShaderProgram no longer declares "
                            + signature
                            + " — a rewritten puddle shader that fails to compile could not fall"
                            + " back to vanilla. Re-verify the patch against the current game"
                            + " source.");
        }
    }
}
