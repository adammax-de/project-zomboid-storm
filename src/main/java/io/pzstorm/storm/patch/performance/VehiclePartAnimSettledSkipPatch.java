package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.skinnedmodel.animation.AnimationPlayer;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}.
 *
 * <p>Routes the {@code AnimationPlayer.Update(float)} call in {@code
 * BaseVehicle.updateAnimationPlayer} through {@link VehiclePartAnimSettle#update}, which skips it
 * while the vehicle's body or part pose is settled and unchanged. {@code BaseVehicle.postupdate}
 * calls {@code updateAnimationPlayer} for the body and for every part model of every loaded vehicle
 * on every frame. In a live 42.21 client JFR {@code postupdate} was 13.2% of MainThread over 24
 * minutes and 19-29% in every minute spent in a car park; 10.5% of MainThread was {@code
 * updateAnimationPlayer}, almost all of it under {@code AnimationPlayer.Update}. The other 2.2% is
 * {@code ModelInfo.getAnimationPlayer}'s per-call model lookup, which this patch leaves alone.
 *
 * <p>Only that one call site is rewritten. Character animation players are untouched, and the rest
 * of {@code updateAnimationPlayer} (removing finished tracks, driving window tracks from {@code
 * getOpenDelta()}, starting the held Opened/Closed track) still runs every frame, so every input
 * change it makes is seen by the fingerprint on the next frame, where vanilla would see it too.
 *
 * <p>PZ-update re-validation: {@code updateAnimationPlayer} still calling {@code
 * AnimationPlayer.Update(float)}, and the {@code AnimationTrack}/{@code AnimationPlayer} accessors
 * the fingerprint reads.
 */
public class VehiclePartAnimSettledSkipPatch extends StormClassTransformer {

    public VehiclePartAnimSettledSkipPatch() {
        super("zombie.vehicles.BaseVehicle");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        try {
            return builder.visit(
                    MemberSubstitution.relaxed()
                            .method(
                                    ElementMatchers.isDeclaredBy(AnimationPlayer.class)
                                            .and(ElementMatchers.named("Update"))
                                            .and(ElementMatchers.takesArguments(float.class)))
                            .replaceWith(
                                    VehiclePartAnimSettle.class.getDeclaredMethod(
                                            "update", AnimationPlayer.class, float.class))
                            .on(ElementMatchers.named("updateAnimationPlayer")));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(
                    "Failed to setup MemberSubstitution for BaseVehicle part animation skip", e);
        }
    }
}
