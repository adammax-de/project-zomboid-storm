package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.logging.StormLogger;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL, CLIENT-SIDE. On by default under {@code -Dstorm.experimental.clientperf=true};
 * {@code -Dstorm.experimental.clientperf.spriteRing=false} skips registration.
 *
 * <p>{@code SpriteRenderer.RingBuffer.create()} sizes the sprite ring as 128 buffers of 65536 bytes
 * (256 of 262144 with {@code Core.debug}), so a busy frame flushes and draws every 1820 vertices.
 * This patch passes the value of each {@code bufferSize} and {@code numBuffers} store in {@code
 * create()} through {@link io.pzstorm.storm.advice.persistentvbo.SpriteRingSizing}, which returns
 * 16 buffers of 1 MiB (29127 vertices each) when the GL context can persistently map buffers.
 * Vanilla then derives {@code bufferSizeInVertices}, {@code indexBufferSize} and every array from
 * the two fields, so the patch repeats no vanilla formula. {@code
 * -Dstorm.experimental.clientperf.spriteRingBufferBytes} and {@code
 * -Dstorm.experimental.clientperf.spriteRingBuffers} override the two numbers. The size is clamped
 * to at most 2359296 bytes, which is 65536 vertices, the range of the ring's unsigned-short
 * indices. The count is clamped to at least 8, at most vanilla's, and to 32 MiB for the whole
 * vertex ring.
 *
 * <p>Why a client bytecode patch: the two sizes are literals inside a package-private method that
 * runs once on the render thread. No Lua and no server change reaches them.
 *
 * <p>Fail-soft: {@link #transform(byte[])} never throws. It scans the raw class first and returns
 * the input bytes, with one error line, unless {@code create()} exists, stores both fields and
 * holds the {@code 36L} vertex stride. Any throwable from the weave, or a wrapped-store count that
 * differs from the scan, also returns the input bytes. The helper catches every throwable of its
 * own and then returns vanilla's values. A context without persistent mapping keeps vanilla sizing,
 * and so does a vanilla size already at or above the configured one.
 *
 * <p>GPU memory on the persistent path: a buffer pair is 1048576 + 87381 bytes, and 16 pairs times
 * {@link io.pzstorm.storm.advice.persistentvbo.PersistentVboSupport#SLOTS} slots is about 69 MiB.
 *
 * <p>Re-validate on each game update: {@code create()} still stores {@code bufferSize} ({@code J})
 * and {@code numBuffers} ({@code I}) before anything reads them, and still derives the vertex
 * capacity as {@code bufferSize / 36L} and the index buffer as three bytes per vertex; {@code
 * add()} still flushes on {@code bufferSizeInVertices} and writes indices as {@code (short)} of the
 * per-buffer vertex cursor; {@code drawElements} still draws with {@code GL_UNSIGNED_SHORT} (5123);
 * {@code next()} still wraps on {@code numBuffers}; {@code postRender()} still begins the ring
 * twice a frame; and the size cap stays under {@code PersistentVboSupport.MAX_SIZE}.
 */
public class SpriteRendererRingBufferSizingPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.core.SpriteRenderer$RingBuffer";
    private static final String OWNER = "zombie/core/SpriteRenderer$RingBuffer";
    private static final String HELPER = "io.pzstorm.storm.advice.persistentvbo.SpriteRingSizing";
    private static final String HELPER_INTERNAL = HELPER.replace('.', '/');

    private static final String CREATE = "create";
    private static final String CREATE_DESCRIPTOR = "()V";
    private static final String SIZE_FIELD = "bufferSize";
    private static final String COUNT_FIELD = "numBuffers";
    private static final long VERTEX_STRIDE = 36L;

    private final StoreWrapper wrapper = new StoreWrapper();

    public SpriteRendererRingBufferSizingPatch() {
        super(TARGET);
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        try {
            CreateShape shape = CreateShape.scan(rawClass);
            String mismatch = shape.mismatch();
            if (mismatch != null) {
                StormLogger.LOGGER.error(
                        "SpriteRendererRingBufferSizingPatch: {}. The sprite ring keeps vanilla"
                                + " sizing. Re-verify the patch against the current game source.",
                        mismatch);
                return rawClass;
            }
            wrapper.sizeCalls = 0;
            wrapper.countCalls = 0;
            byte[] woven = super.transform(rawClass);
            if (wrapper.sizeCalls != shape.sizeStores || wrapper.countCalls != shape.countStores) {
                StormLogger.LOGGER.error(
                        "SpriteRendererRingBufferSizingPatch: wrapped {} of {} bufferSize stores"
                                + " and {} of {} numBuffers stores. The sprite ring keeps vanilla"
                                + " sizing.",
                        wrapper.sizeCalls,
                        shape.sizeStores,
                        wrapper.countCalls,
                        shape.countStores);
                return rawClass;
            }
            StormLogger.LOGGER.info(
                    "SpriteRendererRingBufferSizingPatch wrapped {} bufferSize store(s) and {}"
                            + " numBuffers store(s) in RingBuffer.create()",
                    wrapper.sizeCalls,
                    wrapper.countCalls);
            return woven;
        } catch (Throwable t) {
            try {
                StormLogger.LOGGER.error(
                        "SpriteRendererRingBufferSizingPatch failed to weave. The sprite ring"
                                + " keeps vanilla sizing.",
                        t);
            } catch (Throwable ignored) {
                // The class must still load when the logger is what failed.
            }
            return rawClass;
        }
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription helper = typePool.describe(HELPER).resolve();
        requireHelper(helper, SIZE_FIELD, long.class);
        requireHelper(helper, COUNT_FIELD, int.class);
        return builder.visit(
                new AsmVisitorWrapper.ForDeclaredMethods()
                        .method(
                                ElementMatchers.named(CREATE)
                                        .and(ElementMatchers.takesNoArguments())
                                        .and(ElementMatchers.returns(void.class)),
                                wrapper));
    }

    private static void requireHelper(TypeDescription helper, String name, Class<?> type) {
        ElementMatcher.Junction<MethodDescription> matcher =
                ElementMatchers.named(name)
                        .and(ElementMatchers.isPublic())
                        .and(ElementMatchers.isStatic())
                        .and(ElementMatchers.takesArguments(type))
                        .and(ElementMatchers.returns(type));
        if (!helper.isPublic() || helper.getDeclaredMethods().filter(matcher).isEmpty()) {
            throw new IllegalStateException(
                    HELPER
                            + " does not declare public static "
                            + type
                            + " "
                            + name
                            + "("
                            + type
                            + ")");
        }
    }

    private static boolean isSizeStore(int opcode, String owner, String name, String descriptor) {
        return opcode == Opcodes.PUTFIELD
                && OWNER.equals(owner)
                && SIZE_FIELD.equals(name)
                && "J".equals(descriptor);
    }

    private static boolean isCountStore(int opcode, String owner, String name, String descriptor) {
        return opcode == Opcodes.PUTFIELD
                && OWNER.equals(owner)
                && COUNT_FIELD.equals(name)
                && "I".equals(descriptor);
    }

    /** What the raw {@code create()} looks like, read before any weaving. */
    private static final class CreateShape {

        boolean createFound;
        boolean strideFound;
        int sizeStores;
        int countStores;

        static CreateShape scan(byte[] rawClass) {
            CreateShape shape = new CreateShape();
            new ClassReader(rawClass)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    if (!CREATE.equals(name)
                                            || !CREATE_DESCRIPTOR.equals(descriptor)) {
                                        return null;
                                    }
                                    shape.createFound = true;
                                    return shape.new CreateScanner();
                                }
                            },
                            ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            return shape;
        }

        String mismatch() {
            if (!createFound) {
                return "SpriteRenderer.RingBuffer no longer declares create()V";
            }
            if (sizeStores == 0) {
                return "create() no longer stores the long field bufferSize";
            }
            if (countStores == 0) {
                return "create() no longer stores the int field numBuffers";
            }
            if (!strideFound) {
                return "create() no longer divides by the 36-byte vertex stride";
            }
            return null;
        }

        private final class CreateScanner extends MethodVisitor {

            CreateScanner() {
                super(Opcodes.ASM9);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                if (isSizeStore(opcode, owner, name, descriptor)) {
                    sizeStores++;
                } else if (isCountStore(opcode, owner, name, descriptor)) {
                    countStores++;
                }
            }

            @Override
            public void visitLdcInsn(Object value) {
                if (value instanceof Long && (Long) value == VERTEX_STRIDE) {
                    strideFound = true;
                }
            }
        }
    }

    /** Puts the matching helper call in front of each store of the two size fields. */
    private static final class StoreWrapper
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {

        int sizeCalls;
        int countCalls;

        @Override
        public MethodVisitor wrap(
                TypeDescription instrumentedType,
                MethodDescription instrumentedMethod,
                MethodVisitor methodVisitor,
                Implementation.Context implementationContext,
                TypePool typePool,
                int writerFlags,
                int readerFlags) {
            return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                @Override
                public void visitFieldInsn(
                        int opcode, String owner, String name, String descriptor) {
                    if (isSizeStore(opcode, owner, name, descriptor)) {
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC, HELPER_INTERNAL, SIZE_FIELD, "(J)J", false);
                        StoreWrapper.this.sizeCalls++;
                    } else if (isCountStore(opcode, owner, name, descriptor)) {
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC, HELPER_INTERNAL, COUNT_FIELD, "(I)I", false);
                        StoreWrapper.this.countCalls++;
                    }
                    super.visitFieldInsn(opcode, owner, name, descriptor);
                }
            };
        }
    }
}
