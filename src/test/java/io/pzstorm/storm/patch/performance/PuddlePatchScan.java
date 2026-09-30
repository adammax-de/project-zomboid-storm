package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.core.StormClassTransformer;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/**
 * Shared bytecode scan of the puddle patch tests. Applies one patch to the real game class and
 * records every method call of every method, keyed by {@code name + descriptor}.
 *
 * <p>Uses ByteBuddy's bundled ASM because the standalone {@code org.ow2.asm:asm:9.1} test
 * dependency is too old to read the game's class files.
 */
final class PuddlePatchScan {

    private final Map<String, List<String>> callsByMethod = new HashMap<>();

    private PuddlePatchScan() {}

    static PuddlePatchScan weave(StormClassTransformer patch, String targetInternalName)
            throws Exception {
        byte[] rawClass;
        try (InputStream is =
                PuddlePatchScan.class
                        .getClassLoader()
                        .getResourceAsStream(targetInternalName + ".class")) {
            assertNotNull(is, targetInternalName + ".class must be on the test classpath");
            rawClass = is.readAllBytes();
        }
        byte[] transformed = patch.transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > rawClass.length, "the patch must add bytecode");

        PuddlePatchScan scan = new PuddlePatchScan();
        new ClassReader(transformed)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                final List<String> calls = new ArrayList<>();
                                scan.callsByMethod.put(name + descriptor, calls);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        calls.add(owner + "." + mName);
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return scan;
    }

    boolean hasMethod(String method) {
        return callsByMethod.containsKey(method);
    }

    /** Calls to {@code owner.name} inside {@code method}. */
    int calls(String method, String owner, String name) {
        int count = 0;
        for (String call : callsByMethod.getOrDefault(method, List.of())) {
            if (call.equals(owner + "." + name)) {
                count++;
            }
        }
        return count;
    }

    /** Calls to any method of {@code owner} outside the given methods. */
    int callsOutside(String owner, String... methods) {
        int count = 0;
        for (Map.Entry<String, List<String>> entry : callsByMethod.entrySet()) {
            boolean excluded = false;
            for (String method : methods) {
                excluded |= method.equals(entry.getKey());
            }
            if (excluded) {
                continue;
            }
            for (String call : entry.getValue()) {
                if (call.startsWith(owner + ".")) {
                    count++;
                }
            }
        }
        return count;
    }
}
