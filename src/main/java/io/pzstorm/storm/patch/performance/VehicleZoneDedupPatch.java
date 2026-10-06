package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

public class VehicleZoneDedupPatch extends StormClassTransformer {
    public VehicleZoneDedupPatch() {
        super("zombie.iso.IsoMetaGrid");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(
                                                "io.pzstorm.storm.advice.serverload.VehicleZoneDedupAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("checkVehiclesZones")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
