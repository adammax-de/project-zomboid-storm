package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.core.StormClassTransformers;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import sun.misc.Unsafe;

final class ServerLoadTestSupport {
    private ServerLoadTestSupport() {}

    static byte[] transformed(StormClassTransformer patch) throws Exception {
        return patch.transform(original(patch));
    }

    static byte[] original(StormClassTransformer patch) throws Exception {
        try (InputStream in =
                ServerLoadTestSupport.class
                        .getClassLoader()
                        .getResourceAsStream(patch.getClassName().replace('.', '/') + ".class")) {
            if (in == null) throw new AssertionError("Missing game class " + patch.getClassName());
            return in.readAllBytes();
        }
    }

    @SuppressWarnings("unchecked")
    static Class<?> woven(StormClassTransformer patch) throws Exception {
        // Execute the final class after every registered patch, in production registration order.
        Field field = StormClassTransformers.class.getDeclaredField("TRANSFORMERS");
        field.setAccessible(true);
        Map<String, List<StormClassTransformer>> registered =
                (Map<String, List<StormClassTransformer>>) field.get(null);
        List<StormClassTransformer> patches = registered.get(patch.getClassName());
        if (patches == null || patches.stream().noneMatch(p -> p.getClass() == patch.getClass()))
            throw new AssertionError("Server load patch must be registered: " + patch.getClass());
        byte[] bytes = original(patch);
        for (StormClassTransformer entry : patches) bytes = entry.transform(bytes);
        return new ByteArrayClassLoader.ChildFirst(
                        ServerLoadTestSupport.class.getClassLoader(),
                        Map.of(patch.getClassName(), bytes),
                        ByteArrayClassLoader.PersistenceHandler.MANIFEST)
                .loadClass(patch.getClassName());
    }

    static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    static int calls(byte[] bytes, String method, String owner, String name) {
        int[] count = {0};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String n,
                                    String desc,
                                    String sig,
                                    String[] exceptions) {
                                if (!n.equals(method)) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int op, String o, String n, String d, boolean iface) {
                                        if (owner.equals(o) && name.equals(n)) count[0]++;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }
}
