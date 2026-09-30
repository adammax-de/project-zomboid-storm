package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.lwjgl.util.vector.Matrix4f;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;

/**
 * Stands in for the {@code AnimationPlayer.Update(float)} call inside {@code
 * BaseVehicle.updateAnimationPlayer}, see {@link VehiclePartAnimSettledSkipPatch}.
 *
 * <p>Every loaded vehicle re-runs the full pose pipeline for its body and for every part model
 * (doors, hood, trunk, windows) on every frame, whether or not anything is moving. A parked
 * vehicle's part tracks are held poses ({@code isPlaying == false}), so each of those updates
 * recomputes the transforms it computed the frame before.
 *
 * <p>An update is skipped only when all of the following hold:
 *
 * <ul>
 *   <li>no track on the player is playing, the player has no parent player, and it has at most
 *       {@link #MAX_TRACKS} tracks;
 *   <li>the skinning data and every track's identity, clip, current time, blend weight and reverse
 *       flag are the same as when the player last really updated;
 *   <li>the last {@link #SETTLED_AFTER} real updates ran on those same inputs and each produced
 *       model transforms bit-identical to the update before it, so the pose has reached a fixed
 *       point rather than still blending towards one;
 *   <li>fewer than {@link #MAX_SKIPS} updates in a row have been skipped already.
 * </ul>
 *
 * Anything else runs the vanilla update, so a door opening, a window moving or a new track is
 * picked up on the frame vanilla would pick it up. The model transforms and the skin transform
 * cache are left exactly as the last real update wrote them, which is what the renderer reads.
 *
 * <p>State is kept in a weak map keyed on the player, so it dies with the player; a pooled player
 * reused for another vehicle changes its skinning data or tracks and starts from zero. Main thread
 * only in practice ({@code MovingObjectUpdateScheduler.postupdate}); the map is synchronized
 * anyway. Fails soft: any exception in this class disables it for the session and every later call
 * is the vanilla update.
 */
public final class VehiclePartAnimSettle {

    /** Real updates in a row that must reproduce the previous pose before skipping starts. */
    static final int SETTLED_AFTER = 2;

    /** Skipped updates in a row before one real update is forced, about a second at 60 fps. */
    static final int MAX_SKIPS = 59;

    /** Players with more tracks than this always update. Vehicle part models carry one. */
    static final int MAX_TRACKS = 4;

    private static final Map<AnimationPlayer, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean disabled;

    /** Diagnostics only: updates skipped and updates run through this class. */
    static long skipped;

    static long updated;

    private VehiclePartAnimSettle() {}

    public static void update(AnimationPlayer player, float deltaT) {
        if (disabled) {
            player.Update(deltaT);
            return;
        }
        State state;
        boolean sameInputs;
        try {
            state = STATES.computeIfAbsent(player, p -> new State());
            sameInputs = state.sameInputs(player);
            if (sameInputs && state.settled >= SETTLED_AFTER && state.skips < MAX_SKIPS) {
                state.skips++;
                skipped++;
                return;
            }
            state.capture(player);
        } catch (Throwable t) {
            disable(t);
            player.Update(deltaT);
            return;
        }
        player.Update(deltaT);
        updated++;
        try {
            state.afterUpdate(player, sameInputs);
        } catch (Throwable t) {
            disable(t);
        }
    }

    private static void disable(Throwable t) {
        disabled = true;
        STATES.clear();
        LOGGER.error(
                "VehiclePartAnimSettle failed; vehicle animations run vanilla for this session", t);
    }

    static boolean isDisabled() {
        return disabled;
    }

    /** Test hook: forget every player and counter, and re-enable after a failure. */
    static void reset() {
        STATES.clear();
        disabled = false;
        skipped = 0;
        updated = 0;
    }

    private static final class State {
        private Object skinningData;
        private int trackCount = -1;
        private final AnimationTrack[] tracks = new AnimationTrack[MAX_TRACKS];
        private final Object[] clips = new Object[MAX_TRACKS];
        private final int[] times = new int[MAX_TRACKS];
        private final int[] weights = new int[MAX_TRACKS];
        private final boolean[] reversed = new boolean[MAX_TRACKS];
        private float[] pose;
        private int settled;
        private int skips;

        /** True when the update about to run would see exactly the inputs of the last one. */
        boolean sameInputs(AnimationPlayer player) {
            if (player.parentPlayer != null || !player.updateBones) {
                return false;
            }
            if (player.getSkinningData() != skinningData) {
                return false;
            }
            List<AnimationTrack> list = player.getMultiTrack().getTracks();
            int n = list.size();
            if (n != trackCount || n > MAX_TRACKS) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                AnimationTrack track = list.get(i);
                if (track.isPlaying
                        || track != tracks[i]
                        || track.getClip() != clips[i]
                        || track.reverse != reversed[i]
                        || Float.floatToRawIntBits(track.getCurrentTimeValue()) != times[i]
                        || Float.floatToRawIntBits(track.getBlendWeight()) != weights[i]) {
                    return false;
                }
            }
            return true;
        }

        void capture(AnimationPlayer player) {
            skinningData = player.getSkinningData();
            List<AnimationTrack> list = player.getMultiTrack().getTracks();
            int n = list.size();
            trackCount = n;
            for (int i = 0; i < Math.min(n, MAX_TRACKS); i++) {
                AnimationTrack track = list.get(i);
                tracks[i] = track;
                clips[i] = track.getClip();
                reversed[i] = track.reverse;
                times[i] = Float.floatToRawIntBits(track.getCurrentTimeValue());
                weights[i] = Float.floatToRawIntBits(track.getBlendWeight());
            }
            for (int i = Math.min(n, MAX_TRACKS); i < MAX_TRACKS; i++) {
                tracks[i] = null;
                clips[i] = null;
            }
        }

        void afterUpdate(AnimationPlayer player, boolean sameInputs) {
            skips = 0;
            int bones = player.getModelTransformsCount();
            boolean samePose = sameInputs && pose != null && pose.length == bones * 16;
            if (pose == null || pose.length != bones * 16) {
                pose = new float[bones * 16];
            }
            for (int b = 0; b < bones; b++) {
                Matrix4f m = player.getModelTransformAt(b);
                int o = b * 16;
                samePose &= put(o, m.m00) & put(o + 1, m.m01) & put(o + 2, m.m02);
                samePose &= put(o + 3, m.m03) & put(o + 4, m.m10) & put(o + 5, m.m11);
                samePose &= put(o + 6, m.m12) & put(o + 7, m.m13) & put(o + 8, m.m20);
                samePose &= put(o + 9, m.m21) & put(o + 10, m.m22) & put(o + 11, m.m23);
                samePose &= put(o + 12, m.m30) & put(o + 13, m.m31) & put(o + 14, m.m32);
                samePose &= put(o + 15, m.m33);
            }
            settled = samePose ? Math.min(settled + 1, SETTLED_AFTER) : 0;
        }

        /** Stores {@code v} at {@code i} and reports whether it was already there, bit for bit. */
        private boolean put(int i, float v) {
            boolean same = Float.floatToRawIntBits(pose[i]) == Float.floatToRawIntBits(v);
            pose[i] = v;
            return same;
        }
    }
}
