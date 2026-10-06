package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/** Replaces only the two indexOf calls; every vanilla ID check and diagnostic stays in place. */
public class BuildingRoomIdLookupPatch extends StormClassTransformer {
    public BuildingRoomIdLookupPatch() {
        super("zombie.buildingRooms.BuildingRoomsEditor");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        ElementMatcher.Junction<MethodDescription> method =
                ElementMatchers.named("checkBuildingAndRoomIDs")
                        .and(ElementMatchers.takesArguments(1))
                        .and(
                                ElementMatchers.takesArgument(
                                        0, ElementMatchers.named("zombie.iso.IsoMetaCell")));
        try {
            return builder.visit(
                            MemberSubstitution.relaxed()
                                    .method(
                                            ElementMatchers.named("indexOf")
                                                    .and(
                                                            ElementMatchers.takesArguments(
                                                                    Object.class))
                                                    .and(
                                                            ElementMatchers.isDeclaredBy(
                                                                    ArrayList.class)))
                                    .replaceWith(
                                            RoomIdLookup.class.getMethod(
                                                    "indexOf", ArrayList.class, Object.class))
                                    .on(method))
                    .visit(
                            Advice.to(
                                            typePool.describe(
                                                            "io.pzstorm.storm.advice.serverload.RoomIdLookupAdvice")
                                                    .resolve(),
                                            locator)
                                    .on(method));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Room lookup helper signature changed", e);
        }
    }
}
