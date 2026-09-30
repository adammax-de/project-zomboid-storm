package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.advice.persistentvbo.PersistentVboSupport;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies the patched {@code GLVertexBufferObject} bytecode: the state field exists, the four
 * hooked methods call {@link PersistentVboSupport} and read the state field, and none of the
 * untouched methods ({@code map(int)}, {@code bufferData}, {@code orphan}, {@code bind}) do.
 *
 * <p>Uses ByteBuddy's bundled ASM because the standalone {@code org.ow2.asm:asm:9.1} test
 * dependency is too old to read the game's class files.
 */
class GLVertexBufferObjectPersistentMapPatchTest implements UnitTest {

    private static final String TARGET = "zombie/core/VBO/GLVertexBufferObject";
    private static final String SUPPORT_INTERNAL =
            PersistentVboSupport.class.getName().replace('.', '/');

    private static final String MAP = "map()Ljava/nio/ByteBuffer;";
    private static final String UNMAP = "unmap()Z";
    private static final String CLEAR = "clear()V";
    private static final String DESTROY = "doDestroy()V";

    @Test
    void patchHooksExactlyTheFourLifecycleMethods() throws Exception {
        byte[] rawClass;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(TARGET + ".class")) {
            assertNotNull(is, "GLVertexBufferObject.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new GLVertexBufferObjectPersistentMapPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > rawClass.length);

        Woven woven = scan(transformed);

        assertTrue(
                woven.fields.contains(GLVertexBufferObjectPersistentMapPatch.STATE_FIELD),
                "state field must be defined; got " + woven.fields);

        assertTrue(woven.helperCalls(MAP).contains("adopt"), "map() must call adopt");
        assertTrue(woven.helperCalls(MAP).contains("acquire"), "map() must call acquire");
        assertTrue(woven.helperCalls(MAP).contains("activeName"), "map() must call activeName");
        assertEquals(Set.of("release"), woven.helperCalls(UNMAP));
        assertEquals(Set.of("isPersistent"), woven.helperCalls(CLEAR));
        assertEquals(Set.of("destroy"), woven.helperCalls(DESTROY));

        assertEquals(
                Set.of(MAP, UNMAP, CLEAR, DESTROY),
                woven.helperCallsByMethod.keySet(),
                "helper calls must be woven into exactly the four lifecycle methods");
        assertEquals(
                Set.of(MAP, UNMAP, CLEAR, DESTROY),
                woven.stateReadsByMethod,
                "state field reads must appear in exactly the four lifecycle methods");

        for (String untouched :
                new String[] {
                    "map(I)Ljava/nio/ByteBuffer;",
                    "bufferData(Ljava/nio/ByteBuffer;)V",
                    "orphan()V",
                    "bind()V",
                    "create()V",
                    "getID()I"
                }) {
            assertTrue(
                    woven.seenMethods.contains(untouched),
                    untouched + " must still exist; got " + woven.seenMethods);
            assertTrue(
                    woven.helperCalls(untouched).isEmpty(),
                    untouched + " must not call the persistent-VBO helper");
        }
    }

    private static Woven scan(byte[] classBytes) {
        Woven woven = new Woven();
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public FieldVisitor visitField(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    Object value) {
                                woven.fields.add(name);
                                return null;
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                final String key = name + descriptor;
                                woven.seenMethods.add(key);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (SUPPORT_INTERNAL.equals(owner)) {
                                            woven.helperCallsByMethod
                                                    .computeIfAbsent(key, k -> new HashSet<>())
                                                    .add(mName);
                                        }
                                    }

                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fName, String fDesc) {
                                        if (TARGET.equals(owner)
                                                && GLVertexBufferObjectPersistentMapPatch
                                                        .STATE_FIELD
                                                        .equals(fName)) {
                                            woven.stateReadsByMethod.add(key);
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return woven;
    }

    private static final class Woven {
        final Set<String> fields = new HashSet<>();
        final Set<String> seenMethods = new HashSet<>();
        final Map<String, Set<String>> helperCallsByMethod = new HashMap<>();
        final Set<String> stateReadsByMethod = new HashSet<>();

        Set<String> helperCalls(String method) {
            return helperCallsByMethod.getOrDefault(method, Set.of());
        }
    }
}
