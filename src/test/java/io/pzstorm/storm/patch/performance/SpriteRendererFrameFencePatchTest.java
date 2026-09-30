package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.advice.persistentvbo.PersistentVboSupport;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies the patched {@code SpriteRenderer} bytecode: {@code postRender()} calls {@link
 * PersistentVboSupport#endFrame()} exactly once and no other method in the class calls it.
 */
class SpriteRendererFrameFencePatchTest implements UnitTest {

    private static final String TARGET = "zombie/core/SpriteRenderer";
    private static final String SUPPORT_INTERNAL =
            PersistentVboSupport.class.getName().replace('.', '/');

    @Test
    void postRenderFencesTheFrameExactlyOnce() throws Exception {
        byte[] rawClass;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(TARGET + ".class")) {
            assertNotNull(is, "SpriteRenderer.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new SpriteRendererFrameFencePatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Map<String, Integer> endFrameCalls = countEndFrameCalls(transformed);
        assertEquals(
                Map.of("postRender()V", 1),
                endFrameCalls,
                "endFrame() must be woven once into postRender() and nowhere else");
    }

    private static Map<String, Integer> countEndFrameCalls(byte[] classBytes) {
        Map<String, Integer> counts = new HashMap<>();
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
                                final String key = name + descriptor;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (SUPPORT_INTERNAL.equals(owner)
                                                && "endFrame".equals(mName)) {
                                            counts.merge(key, 1, Integer::sum);
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }
}
