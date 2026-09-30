package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.advice.persistentvbo.SpriteRingSizing;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies the patched {@code SpriteRenderer.RingBuffer} bytecode: every store of {@code
 * bufferSize} and {@code numBuffers} in {@code create()} is fed by the matching {@link
 * SpriteRingSizing} call and nothing else in the class calls the helper. Also pins the vanilla
 * shapes the enlarged buffer relies on, and the fail-soft contract of {@code transform}.
 *
 * <p>Uses ByteBuddy's bundled ASM because the standalone {@code org.ow2.asm:asm:9.1} test
 * dependency is too old to read the game's class files.
 */
class SpriteRendererRingBufferSizingPatchTest implements UnitTest {

    private static final String TARGET = "zombie/core/SpriteRenderer$RingBuffer";
    private static final String HELPER = SpriteRingSizing.class.getName().replace('.', '/');

    private static final String CREATE = "create()V";
    private static final String ADD_PREFIX = "add(";
    private static final String DRAW = "drawElements(IIII)V";

    private static final String SIZE_STORE = "PUTFIELD " + TARGET + ".bufferSize:J";
    private static final String COUNT_STORE = "PUTFIELD " + TARGET + ".numBuffers:I";
    private static final String SIZE_CALL = "INVOKESTATIC " + HELPER + ".bufferSize(J)J";
    private static final String COUNT_CALL = "INVOKESTATIC " + HELPER + ".numBuffers(I)I";

    @Test
    void everySizeStoreInCreateGoesThroughTheHelper() throws Exception {
        byte[] rawClass = readTarget();
        Map<String, List<String>> vanilla = scan(rawClass);
        int sizeStores = count(vanilla.get(CREATE), SIZE_STORE);
        int countStores = count(vanilla.get(CREATE), COUNT_STORE);
        assertTrue(sizeStores >= 1, "vanilla create() must store bufferSize");
        assertTrue(countStores >= 1, "vanilla create() must store numBuffers");

        byte[] transformed = new SpriteRendererRingBufferSizingPatch().transform(rawClass);
        assertNotNull(transformed);
        assertNotSame(rawClass, transformed, "the weave must not have fallen back to vanilla");

        Map<String, List<String>> woven = scan(transformed);
        List<String> create = woven.get(CREATE);
        assertNotNull(create, "create()V must survive the weave");

        assertEquals(sizeStores, count(create, SIZE_STORE));
        assertEquals(countStores, count(create, COUNT_STORE));
        assertEquals(sizeStores, count(create, SIZE_CALL));
        assertEquals(countStores, count(create, COUNT_CALL));
        for (int i = 0; i < create.size(); i++) {
            if (SIZE_STORE.equals(create.get(i))) {
                assertEquals(SIZE_CALL, i == 0 ? null : create.get(i - 1));
            } else if (COUNT_STORE.equals(create.get(i))) {
                assertEquals(COUNT_CALL, i == 0 ? null : create.get(i - 1));
            }
        }

        for (Map.Entry<String, List<String>> method : woven.entrySet()) {
            if (CREATE.equals(method.getKey())) {
                continue;
            }
            for (String instruction : method.getValue()) {
                assertTrue(
                        !instruction.contains(HELPER),
                        method.getKey() + " must not call the sizing helper: " + instruction);
            }
        }

        for (String untouched :
                new String[] {"next()V", "render()V", "growStateRuns()V", "begin()V", DRAW}) {
            assertEquals(
                    vanilla.get(untouched),
                    woven.get(untouched),
                    untouched + " must keep its vanilla instructions");
            assertNotNull(woven.get(untouched), untouched + " must still exist");
        }
        assertEquals(vanilla.get(addKey(vanilla)), woven.get(addKey(woven)));
    }

    /** Initialising the woven class links it, which runs the JVM verifier over {@code create()}. */
    @Test
    void wovenClassPassesTheVerifier() throws Exception {
        byte[] woven = new SpriteRendererRingBufferSizingPatch().transform(readTarget());
        String binaryName = TARGET.replace('/', '.');
        ClassLoader loader =
                new ClassLoader(getClass().getClassLoader()) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (!binaryName.equals(name)) {
                            return super.loadClass(name, resolve);
                        }
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> loaded = findLoadedClass(name);
                            return loaded != null
                                    ? loaded
                                    : defineClass(name, woven, 0, woven.length);
                        }
                    }
                };

        Class<?> ringBuffer = Class.forName(binaryName, true, loader);
        assertSame(loader, ringBuffer.getClassLoader());
    }

    /** Fails on a game update that changes what makes a larger buffer safe. */
    @Test
    void vanillaShapesTheEnlargedBufferReliesOn() throws Exception {
        Map<String, List<String>> vanilla = scan(readTarget());

        assertTrue(
                vanilla.get(CREATE).contains("LDC 36"),
                "create() must derive the vertex capacity from the 36-byte stride");

        List<String> draw = vanilla.get(DRAW);
        assertNotNull(draw, "drawElements(IIII)V must exist");
        assertTrue(draw.contains("INT 5123"), "indices must be drawn as GL_UNSIGNED_SHORT");
        assertEquals(
                1,
                draw.stream()
                        .filter(
                                instruction ->
                                        instruction.startsWith("INVOKESTATIC ")
                                                && instruction.contains(".glDrawRangeElements("))
                        .count(),
                "drawElements must issue one glDrawRangeElements");

        List<String> add = vanilla.get(addKey(vanilla));
        assertTrue(add.contains("I2S"), "add() must write indices as shorts");
        assertTrue(
                add.contains("GETFIELD " + TARGET + ".bufferSizeInVertices:J"),
                "add() must flush on bufferSizeInVertices");
    }

    @Test
    void classWithoutTheSizeFieldsIsReturnedUntouched() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        MethodVisitor create = writer.visitMethod(0, "create", "()V", null, null);
        create.visitCode();
        create.visitInsn(Opcodes.RETURN);
        create.visitMaxs(0, 1);
        create.visitEnd();
        writer.visitEnd();
        byte[] synthetic = writer.toByteArray();

        SpriteRendererRingBufferSizingPatch patch = new SpriteRendererRingBufferSizingPatch();
        assertSame(synthetic, assertDoesNotThrow(() -> patch.transform(synthetic)));
    }

    @Test
    void classWithoutCreateIsReturnedUntouched() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        writer.visitEnd();
        byte[] synthetic = writer.toByteArray();

        SpriteRendererRingBufferSizingPatch patch = new SpriteRendererRingBufferSizingPatch();
        assertSame(synthetic, assertDoesNotThrow(() -> patch.transform(synthetic)));
    }

    @Test
    void garbageBytesAreReturnedUntouched() {
        SpriteRendererRingBufferSizingPatch patch = new SpriteRendererRingBufferSizingPatch();
        byte[] garbage = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        byte[] empty = new byte[0];

        assertSame(garbage, assertDoesNotThrow(() -> patch.transform(garbage)));
        assertSame(empty, assertDoesNotThrow(() -> patch.transform(empty)));
    }

    private byte[] readTarget() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(TARGET + ".class")) {
            assertNotNull(is, "SpriteRenderer$RingBuffer.class must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static String addKey(Map<String, List<String>> methods) {
        List<String> keys = new ArrayList<>();
        for (String key : methods.keySet()) {
            if (key.startsWith(ADD_PREFIX)) {
                keys.add(key);
            }
        }
        assertEquals(1, keys.size(), "RingBuffer must declare exactly one add(...): " + keys);
        return keys.get(0);
    }

    private static int count(List<String> instructions, String wanted) {
        int matches = 0;
        for (String instruction : instructions) {
            if (wanted.equals(instruction)) {
                matches++;
            }
        }
        return matches;
    }

    /** Field, method, constant and plain instructions of every method, in order. */
    private static Map<String, List<String>> scan(byte[] classBytes) {
        Map<String, List<String>> methods = new LinkedHashMap<>();
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                List<String> instructions = new ArrayList<>();
                                methods.put(name + descriptor, instructions);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fName, String fDesc) {
                                        instructions.add(
                                                fieldOpcode(opcode)
                                                        + " "
                                                        + owner
                                                        + "."
                                                        + fName
                                                        + ":"
                                                        + fDesc);
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        instructions.add(
                                                (opcode == Opcodes.INVOKESTATIC
                                                                ? "INVOKESTATIC "
                                                                : "INVOKE ")
                                                        + owner
                                                        + "."
                                                        + mName
                                                        + mDesc);
                                    }

                                    @Override
                                    public void visitLdcInsn(Object value) {
                                        instructions.add("LDC " + value);
                                    }

                                    @Override
                                    public void visitIntInsn(int opcode, int operand) {
                                        instructions.add("INT " + operand);
                                    }

                                    @Override
                                    public void visitInsn(int opcode) {
                                        instructions.add(
                                                opcode == Opcodes.I2S ? "I2S" : "INSN " + opcode);
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return methods;
    }

    private static String fieldOpcode(int opcode) {
        switch (opcode) {
            case Opcodes.PUTFIELD:
                return "PUTFIELD";
            case Opcodes.GETFIELD:
                return "GETFIELD";
            case Opcodes.PUTSTATIC:
                return "PUTSTATIC";
            default:
                return "GETSTATIC";
        }
    }
}
