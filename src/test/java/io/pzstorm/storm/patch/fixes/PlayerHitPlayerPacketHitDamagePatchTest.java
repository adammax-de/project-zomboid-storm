package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;
import zombie.network.fields.hit.WeaponHit;

/**
 * Verifies that {@link PlayerHitPlayerPacketHitDamagePatch} lands on {@code parse} only, and
 * unit-tests the flag clearing in {@link PvpHitsAlwaysDamage}.
 */
class PlayerHitPlayerPacketHitDamagePatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/network/packets/hit/PlayerHitPlayerPacket";
    private static final String HELPER_CLASS = "io/pzstorm/storm/patch/fixes/PvpHitsAlwaysDamage";
    private static final String PARSE_DESC =
            "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V";

    @Test
    void patchLandsOnParseOnly() throws Exception {
        byte[] raw = readClassBytes(TARGET_CLASS + ".class");
        byte[] out = new PlayerHitPlayerPacketHitDamagePatch().transform(raw);
        assertNotNull(out);

        assertEquals(0, countHelperCalls(raw, "parse", PARSE_DESC));
        assertTrue(countHelperCalls(out, "parse", PARSE_DESC) >= 1);
        assertEquals(0, countHelperCalls(out, "process", null));
        assertEquals(0, countHelperCalls(out, "write", null));
    }

    @Test
    void clearsOnlyTheFlaggedHits() throws Exception {
        List<WeaponHit> hits = new ArrayList<>();
        hits.add(hit(true));
        hits.add(hit(false));
        hits.add(hit(true));

        assertEquals(2, PvpHitsAlwaysDamage.clearIgnoreDamage(hits));
        for (WeaponHit hit : hits) {
            assertFalse(ignoreDamage(hit));
        }
        assertEquals(0, PvpHitsAlwaysDamage.clearIgnoreDamage(hits));
    }

    @Test
    void toleratesNullAndForeignEntries() {
        assertEquals(0, PvpHitsAlwaysDamage.clearIgnoreDamage(null));
        assertEquals(0, PvpHitsAlwaysDamage.clearIgnoreDamage(List.of("not a hit")));
    }

    private static WeaponHit hit(boolean ignoreDamage) {
        WeaponHit hit = new WeaponHit();
        hit.set(10f, 1f, 1f, 0f, 1f, false, false, false, ignoreDamage);
        return hit;
    }

    private static boolean ignoreDamage(WeaponHit hit) throws Exception {
        Field field = WeaponHit.class.getDeclaredField("ignoreDamage");
        field.setAccessible(true);
        return field.getBoolean(hit);
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countHelperCalls(byte[] classBytes, String method, String desc) {
        int[] hits = new int[1];
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
                                if (!method.equals(name)
                                        || (desc != null && !desc.equals(descriptor))) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (HELPER_CLASS.equals(owner)) {
                                            hits[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return hits[0];
    }
}
