package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import java.nio.ByteBuffer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.FieldPersistence;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}.
 *
 * <p>Every sprite batch the render thread fills goes through {@code GLVertexBufferObject.map()},
 * which orphans the buffer ({@code glBufferData(NULL)}) and then {@code glMapBufferRange}s it, and
 * through {@code unmap()}, which {@code glUnmapBuffer}s it. The sprite ring does that for a VBO and
 * an IBO per batch, many times a frame. This patch gives each eligible instance {@link
 * io.pzstorm.storm.advice.persistentvbo.PersistentVboSupport#SLOTS} immutable buffers ({@code
 * glBufferStorage}, write + persistent + coherent) that are mapped once and stay mapped for the
 * life of the process. {@code map()} rotates to the next slot, {@code unmap()} records which frame
 * wrote it, and one {@code glFenceSync} per frame (see {@link SpriteRendererFrameFencePatch}) is
 * what a slot waits on before it is written again. {@link SpriteRendererRingBufferSizingPatch}
 * sizes the ring as 16 buffers of 1 MiB, so with 4 slots the reuse distance is 64 batches (512
 * batches of 65 KB under vanilla sizing) and the wait almost never blocks; the puddle pool reuses
 * its buffer 0 every frame, and its slot rotation keeps that wait on a frame four back.
 *
 * <p>Why a client bytecode patch: the cost is inside private GL calls in a Java class on the render
 * thread. No Lua and no server change reaches them.
 *
 * <p>Fail-soft: every advice uses {@code suppress = Throwable.class}, so an advice failure runs the
 * vanilla body. Any exception or GL error during slot allocation disables adoption for the session
 * (one warning), releases the names allocated so far and lets that {@code map()} run vanilla; the
 * vanilla name is untouched until allocation has fully succeeded. An instance that is already
 * persistent stays persistent (immutable storage cannot go back to mutable). If the context lacks
 * ({@code OpenGL44} or {@code GL_ARB_buffer_storage}) and ({@code OpenGL32} or {@code GL_ARB_sync})
 * the patch is a pure pass-through. A missing target method fails the transform at weave time and
 * leaves the class vanilla.
 *
 * <p>GPU memory: four slots for every buffer. The 16 x 1 MiB ring takes about 69 MiB and the puddle
 * pool up to about 13 MiB more. Under vanilla ring sizing the ring takes about 35 MiB, or about 277
 * MiB when {@code Core.debug} enlarges it to 256 x 256 KB.
 *
 * <p>Re-validate on each game update: {@code map()}, {@code unmap()}, {@code clear()} and {@code
 * doDestroy()} on {@code GLVertexBufferObject} and its {@code size}/{@code type}/{@code id}/{@code
 * mapped}/{@code cleared}/{@code buffer} fields; that {@code SpriteRenderer.RingBuffer.next()}
 * still calls {@code bind()} then {@code map()} and draws through {@code bind()}/{@code getID()};
 * and that {@code SpriteRenderer.postRender()} is still the per-frame draw boundary.
 */
public class GLVertexBufferObjectPersistentMapPatch extends StormClassTransformer {

    public static final String STATE_FIELD = "storm$persistentVbo";

    private static final String TARGET = "zombie.core.VBO.GLVertexBufferObject";
    private static final String PKG = "io.pzstorm.storm.advice.persistentvbo.";

    public GLVertexBufferObjectPersistentMapPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        ElementMatcher.Junction<MethodDescription> map =
                ElementMatchers.named("map")
                        .and(ElementMatchers.takesNoArguments())
                        .and(ElementMatchers.returns(ByteBuffer.class));
        ElementMatcher.Junction<MethodDescription> unmap =
                ElementMatchers.named("unmap")
                        .and(ElementMatchers.takesNoArguments())
                        .and(ElementMatchers.returns(boolean.class));
        ElementMatcher.Junction<MethodDescription> clear =
                ElementMatchers.named("clear")
                        .and(ElementMatchers.takesNoArguments())
                        .and(ElementMatchers.returns(void.class));
        ElementMatcher.Junction<MethodDescription> doDestroy =
                ElementMatchers.named("doDestroy")
                        .and(ElementMatchers.takesNoArguments())
                        .and(ElementMatchers.returns(void.class));
        require(target, map, "map()");
        require(target, unmap, "unmap()");
        require(target, clear, "clear()");
        require(target, doDestroy, "doDestroy()");
        return builder.defineField(
                        STATE_FIELD, Object.class, Visibility.PUBLIC, FieldPersistence.TRANSIENT)
                .visit(advice(typePool, locator, "GLVertexBufferObjectMapAdvice").on(map))
                .visit(advice(typePool, locator, "GLVertexBufferObjectUnmapAdvice").on(unmap))
                .visit(advice(typePool, locator, "GLVertexBufferObjectClearAdvice").on(clear))
                .visit(
                        advice(typePool, locator, "GLVertexBufferObjectDestroyAdvice")
                                .on(doDestroy));
    }

    private static Advice advice(TypePool typePool, ClassFileLocator locator, String name) {
        return Advice.to(typePool.describe(PKG + name).resolve(), locator);
    }

    private static void require(
            TypeDescription target,
            ElementMatcher.Junction<MethodDescription> matcher,
            String signature) {
        if (target.getDeclaredMethods().filter(matcher).isEmpty()) {
            throw new IllegalStateException(
                    "GLVertexBufferObjectPersistentMapPatch: GLVertexBufferObject no longer"
                            + " declares "
                            + signature
                            + " — the persistent-mapping hooks would be incomplete. Re-verify"
                            + " the patch against the current game source.");
        }
    }
}
