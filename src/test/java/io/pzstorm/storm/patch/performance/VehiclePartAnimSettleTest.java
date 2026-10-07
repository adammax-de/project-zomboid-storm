package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiConsumer;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Quaternion;
import org.lwjgl.util.vector.Vector3f;
import zombie.asset.Asset;
import zombie.core.skinnedmodel.animation.AnimationClip;
import zombie.core.skinnedmodel.animation.AnimationMultiTrack;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.core.skinnedmodel.animation.Keyframe;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.SkinningData;

/**
 * Drives the real {@code AnimationPlayer} with a small three-bone skeleton through the sequence
 * {@code BaseVehicle.updateAnimationPlayer} runs every frame (update, drop finished playing tracks,
 * set window track time), once with the vanilla {@code Update} call and once through {@link
 * VehiclePartAnimSettle}, and requires the two players' model transforms to be bit-identical after
 * every frame.
 *
 * <p>{@link #naiveSkipDiverges()} runs the same script with a skip that ignores the fingerprint and
 * requires it to diverge, so the comparison can fail.
 */
class VehiclePartAnimSettleTest implements UnitTest {

    private static final float DT = 0.016666668F * 0.8F;
    private static final String DOOR = "DoorSwing";
    private static final String HOOD = "HoodLift";

    private Model model;

    @BeforeEach
    void setUp() throws Exception {
        VehiclePartAnimSettle.reset();
        model = fakeModel();
    }

    @AfterEach
    void tearDown() {
        VehiclePartAnimSettle.reset();
    }

    @Test
    void patchedPlayerMatchesVanillaEveryFrame() {
        Script result = runScript(VehiclePartAnimSettle::update);
        assertEquals(0, result.mismatchedFrames, "first mismatch: " + result.firstMismatch);
        assertFalse(VehiclePartAnimSettle.isDisabled(), "the settle helper must not have failed");
        assertTrue(
                VehiclePartAnimSettle.skipped > result.frames / 2,
                "held phases dominate the script, so most updates must be skipped; skipped "
                        + VehiclePartAnimSettle.skipped
                        + " of "
                        + result.frames);
        assertEquals(0, result.skipsWhilePlaying, "an update was skipped while a track played");
    }

    @Test
    void heldPoseStillUpdatesAboutOnceASecond() {
        Rig vanilla = new Rig(model, AnimationPlayer::Update);
        Rig patched = new Rig(model, VehiclePartAnimSettle::update);
        for (Rig rig : new Rig[] {vanilla, patched}) {
            rig.play(DOOR, false, false, 1.0F);
            rig.track.setCurrentTimeValue(0.5F);
        }
        int frames = 300;
        for (int i = 0; i < frames; i++) {
            vanilla.frame();
            patched.frame();
            assertEquals(pose(vanilla.player), pose(patched.player), "frame " + i);
        }
        // Three updates to settle (frames 1-3), then one real update in every 60 frames: frames
        // 63, 123, 183 and 243. Written out rather than derived from the constants under test.
        assertEquals(7, VehiclePartAnimSettle.updated, "real updates over " + frames + " frames");
        assertEquals(frames - 7, VehiclePartAnimSettle.skipped);
    }

    @Test
    void naiveSkipDiverges() {
        // Skips whenever no track is playing and the player has updated once: what a "parked
        // vehicle" shortcut without the fingerprint would do.
        List<AnimationPlayer> seen = new ArrayList<>();
        Script result =
                runScript(
                        (player, dt) -> {
                            boolean playing = false;
                            for (AnimationTrack t : player.getMultiTrack().getTracks()) {
                                playing |= t.isPlaying;
                            }
                            if (!playing && seen.contains(player)) {
                                return;
                            }
                            seen.add(player);
                            player.Update(dt);
                        });
        assertTrue(
                result.mismatchedFrames > 0,
                "the script must be able to catch a skip that ignores its inputs");
    }

    @Test
    void childFollowsItsParentPlayer() {
        Rig vanillaParent = new Rig(model, AnimationPlayer::Update);
        Rig patchedParent = new Rig(model, AnimationPlayer::Update);
        Rig vanilla = new Rig(model, AnimationPlayer::Update);
        Rig patched = new Rig(model, VehiclePartAnimSettle::update);
        vanilla.player.parentPlayer = vanillaParent.player;
        patched.player.parentPlayer = patchedParent.player;
        for (Rig r : new Rig[] {vanillaParent, patchedParent, vanilla, patched}) {
            r.play(DOOR, false, false, 1.0F);
        }
        for (int i = 0; i < 200; i++) {
            if (i % 25 == 0) {
                vanillaParent.track.setCurrentTimeValue((i % 100) / 100F);
                patchedParent.track.setCurrentTimeValue((i % 100) / 100F);
            }
            vanillaParent.frame();
            patchedParent.frame();
            vanilla.frame();
            patched.frame();
            assertEquals(pose(vanilla.player), pose(patched.player), "frame " + i);
        }
        assertEquals(0, VehiclePartAnimSettle.skipped, "a player with a parent never skips");
    }

    @Test
    void timeChangesThePose() {
        Rig rig = new Rig(model, AnimationPlayer::Update);
        rig.play(DOOR, false, false, 1.0F);
        rig.frame();
        rig.frame();
        List<Integer> closed = pose(rig.player);
        rig.track.setCurrentTimeValue(1.0F);
        rig.frame();
        rig.frame();
        assertFalse(
                closed.equals(pose(rig.player)),
                "the test clip must move the pose, or every comparison above is vacuous");
    }

    @Test
    void patchRoutesOnlyTheUpdateCallInUpdateAnimationPlayer() throws Exception {
        byte[] raw;
        try (InputStream is =
                getClass()
                        .getClassLoader()
                        .getResourceAsStream("zombie/vehicles/BaseVehicle.class")) {
            assertNotNull(is, "BaseVehicle.class must be on the test classpath");
            raw = is.readAllBytes();
        }
        int[] vanilla = countCalls(raw);
        assertEquals(
                1, vanilla[0], "vanilla updateAnimationPlayer calls AnimationPlayer.Update once");

        int[] patched = countCalls(new VehiclePartAnimSettledSkipPatch().transform(raw));
        assertEquals(
                0, patched[0], "no direct AnimationPlayer.Update left in updateAnimationPlayer");
        assertEquals(1, patched[1], "one call to VehiclePartAnimSettle.update");
        assertEquals(0, patched[2], "the helper must not appear outside updateAnimationPlayer");
    }

    // ------------------------------------------------------------------ the script

    private static final class Script {
        int frames;
        int mismatchedFrames;
        int skipsWhilePlaying;
        String firstMismatch = "none";
    }

    /**
     * One vanilla rig and one rig using {@code update}, fed identical operations. Every change
     * after the first comes after a hold long enough to settle, and changes one input only, so each
     * fingerprint field has a phase where it alone decides: a held pose, a playing clip that
     * finishes and is replaced by a held one, window-style time sets after the update, a blend
     * weight change, a reverse flip, a clip swap at the same time, no tracks at all, two blended
     * tracks with a weight change on the second, and the second track removed.
     */
    private Script runScript(BiConsumer<AnimationPlayer, Float> update) {
        Rig vanilla = new Rig(model, AnimationPlayer::Update);
        Rig patched = new Rig(model, update);
        Rig[] both = {vanilla, patched};
        Script s = new Script();

        for (Rig r : both) r.play(DOOR, false, false, 1.0F);
        run(s, vanilla, patched, 120, null);

        for (Rig r : both) r.play(DOOR, true, false, 1.5F);
        run(s, vanilla, patched, 80, null);
        assertEquals(0, vanilla.player.getMultiTrack().getTrackCount(), "door opening finished");
        for (Rig r : both) {
            r.play(DOOR, false, false, 1.0F);
            r.track.setCurrentTimeValue(1.0F);
        }
        run(s, vanilla, patched, 120, null);

        // Window: BaseVehicle sets the track time after the update, from getOpenDelta().
        run(
                s,
                vanilla,
                patched,
                150,
                (r, i) -> {
                    if (i % 20 < 10) {
                        r.track.setCurrentTimeValue(r.track.getDuration() * (i % 7) / 7F);
                    }
                });
        run(s, vanilla, patched, 60, null);

        for (Rig r : both) r.track.setBlendWeight(0.5F);
        run(s, vanilla, patched, 100, null);

        for (Rig r : both) r.track.reverse = true;
        run(s, vanilla, patched, 100, null);

        for (Rig r : both) {
            r.track.reverse = false;
            r.track.setBlendWeight(1.0F);
            r.track.setCurrentTimeValue(0.4F);
        }
        run(s, vanilla, patched, 100, null);

        // Same time, weight and direction: only the clip (and maybe the pooled track) changes.
        for (Rig r : both) {
            r.play(HOOD, false, false, 1.0F);
            r.track.setCurrentTimeValue(0.4F);
        }
        run(s, vanilla, patched, 100, null);

        for (Rig r : both) r.player.getMultiTrack().removeTrack(r.track);
        run(s, vanilla, patched, 60, null);

        // Blending walks tracks from the heaviest down until the weight is used up, so the first
        // track must leave room for the second to count.
        AnimationTrack[] second = new AnimationTrack[2];
        for (int k = 0; k < 2; k++) {
            Rig r = both[k];
            r.play(DOOR, false, false, 1.0F);
            r.track.setCurrentTimeValue(1.0F);
            r.track.setBlendWeight(0.5F);
            second[k] = r.player.play(HOOD, false);
            second[k].setBlendWeight(0.3F);
            second[k].isPlaying = false;
            second[k].setCurrentTimeValue(0.75F);
        }
        run(s, vanilla, patched, 120, null);
        for (AnimationTrack t : second) t.setBlendWeight(0.45F);
        run(s, vanilla, patched, 100, null);
        for (int k = 0; k < 2; k++) both[k].player.getMultiTrack().removeTrack(second[k]);
        run(s, vanilla, patched, 100, null);
        return s;
    }

    private interface Tweak {
        void apply(Rig rig, int frame);
    }

    private static void run(Script s, Rig vanilla, Rig patched, int frames, Tweak afterUpdate) {
        for (int i = 0; i < frames; i++) {
            boolean playing = anyPlaying(patched.player);
            long skippedBefore = VehiclePartAnimSettle.skipped;
            vanilla.frame();
            patched.frame();
            if (playing && VehiclePartAnimSettle.skipped != skippedBefore) {
                s.skipsWhilePlaying++;
            }
            if (afterUpdate != null) {
                afterUpdate.apply(vanilla, i);
                afterUpdate.apply(patched, i);
            }
            s.frames++;
            if (!pose(vanilla.player).equals(pose(patched.player))) {
                if (s.mismatchedFrames++ == 0) {
                    s.firstMismatch = "script frame " + s.frames;
                }
            }
        }
    }

    private static boolean anyPlaying(AnimationPlayer player) {
        for (AnimationTrack t : player.getMultiTrack().getTracks()) {
            if (t.isPlaying) {
                return true;
            }
        }
        return false;
    }

    /** One part model: an animation player plus the track BaseVehicle keeps in ModelInfo. */
    private static final class Rig {
        final AnimationPlayer player;
        final BiConsumer<AnimationPlayer, Float> update;
        AnimationTrack track;

        Rig(Model model, BiConsumer<AnimationPlayer, Float> update) {
            this.player = AnimationPlayer.alloc(model);
            this.update = update;
            assertTrue(player.isReady(), "the fake model must make the player ready");
        }

        /** Mirrors {@code BaseVehicle.playPartAnim}. */
        void play(String clip, boolean animate, boolean reverse, float rate) {
            AnimationMultiTrack multiTrack = player.getMultiTrack();
            if (track != null && multiTrack.getIndexOfTrack(track) != -1) {
                multiTrack.removeTrack(track);
            }
            track = player.play(clip, false);
            assertNotNull(track, clip);
            track.setBlendWeight(1.0F);
            track.setSpeedDelta(rate);
            track.isPlaying = animate;
            track.reverse = reverse;
        }

        /** Mirrors the per-frame body of {@code BaseVehicle.updateAnimationPlayer}. */
        void frame() {
            update.accept(player, DT);
            AnimationMultiTrack multiTrack = player.getMultiTrack();
            for (int i = 0; i < multiTrack.getTrackCount(); i++) {
                AnimationTrack t = multiTrack.getTracks().get(i);
                if (t.isPlaying && t.isFinished()) {
                    multiTrack.removeTrackAt(i);
                    i--;
                }
            }
        }
    }

    private static List<Integer> pose(AnimationPlayer player) {
        List<Integer> bits = new ArrayList<>();
        for (int b = 0; b < player.getModelTransformsCount(); b++) {
            Matrix4f m = player.getModelTransformAt(b);
            float[] v = {
                m.m00, m.m01, m.m02, m.m03, m.m10, m.m11, m.m12, m.m13,
                m.m20, m.m21, m.m22, m.m23, m.m30, m.m31, m.m32, m.m33
            };
            for (float f : v) {
                bits.add(Float.floatToRawIntBits(f));
            }
        }
        return bits;
    }

    // ------------------------------------------------------------------ fixtures

    /** Root, a hinge (the door) and a tip, with two clips that move the hinge differently. */
    private static Model fakeModel() throws Exception {
        List<Matrix4f> identity = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            identity.add(new Matrix4f());
        }
        HashMap<String, Integer> boneIndices = new HashMap<>();
        boneIndices.put("Root", 0);
        boneIndices.put("Hinge", 1);
        boneIndices.put("Tip", 2);
        HashMap<String, AnimationClip> clips = new HashMap<>();
        clips.put(DOOR, clip(DOOR, 1.2F, 1.0F));
        clips.put(HOOD, clip(HOOD, -0.8F, 0.6F));
        SkinningData data =
                new SkinningData(
                        clips,
                        identity,
                        new ArrayList<>(identity),
                        new ArrayList<>(identity),
                        List.of(-1, 0, 1),
                        boneIndices);

        Model model = (Model) allocate(Model.class);
        Class<?> privClass = Class.forName("zombie.asset.Asset$PRIVATE");
        Object priv = allocate(privClass);
        Field state = privClass.getDeclaredField("currentState");
        state.setAccessible(true);
        state.set(priv, Asset.State.READY);
        Field privField = Asset.class.getDeclaredField("priv");
        privField.setAccessible(true);
        privField.set(model, priv);
        model.tag = data;
        return model;
    }

    private static AnimationClip clip(String name, float hingeAngle, float tipLift) {
        List<Keyframe> frames = new ArrayList<>();
        float[] times = {0.0F, 0.5F, 1.0F};
        for (float t : times) {
            for (int bone = 0; bone < 3; bone++) {
                Quaternion rot = new Quaternion();
                Vector3f pos = new Vector3f(bone == 0 ? 0 : 1, 0, 0);
                if (bone == 1) {
                    rot.setFromAxisAngle(
                            new org.lwjgl.util.vector.Vector4f(0, 1, 0, hingeAngle * t));
                }
                if (bone == 2) {
                    pos.y = tipLift * t * t;
                }
                Keyframe k = new Keyframe(pos, rot, new Vector3f(1, 1, 1));
                k.bone = bone;
                k.boneName = bone == 0 ? "Root" : bone == 1 ? "Hinge" : "Tip";
                k.time = t;
                frames.add(k);
            }
        }
        return new AnimationClip(1.0F, frames, name, true);
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        return allocate.invoke(theUnsafe.get(null), type);
    }

    /**
     * [0] direct AnimationPlayer.Update calls in updateAnimationPlayer, [1] helper calls there, [2]
     * helper calls in any other method.
     */
    private static int[] countCalls(byte[] classBytes) {
        int[] counts = new int[3];
        String helper = "io/pzstorm/storm/patch/performance/VehiclePartAnimSettle";
        String player = "zombie/core/skinnedmodel/animation/AnimationPlayer";
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] ex) {
                                boolean target = "updateAnimationPlayer".equals(name);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int op, String owner, String m, String d, boolean itf) {
                                        if (target && owner.equals(player) && m.equals("Update")) {
                                            counts[0]++;
                                        }
                                        if (owner.equals(helper) && m.equals("update")) {
                                            counts[target ? 1 : 2]++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return counts;
    }
}
