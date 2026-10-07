package io.pzstorm.storm.patch.popman;

import io.pzstorm.storm.core.StormClassTransformer;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces a class's {@code n_*} natives with forwarders to same-named static methods on a Java
 * facade. ByteBuddy's {@code redefine} clears {@code ACC_NATIVE} and installs the body, so the JVM
 * never looks the symbol up in {@code PZPopMan64}.
 *
 * <p>Natives are bound by name rather than a wildcard so a game update that adds or renames one
 * fails the weave test instead of silently leaving a live JNI call in a Java-backed class.
 */
public abstract class NativeFacadePatch extends StormClassTransformer {

    private final Class<?> facade;
    private final String[] natives;

    protected NativeFacadePatch(String target, Class<?> facade, String[] natives) {
        super(target);
        this.facade = facade;
        this.natives = natives;
    }

    public String[] natives() {
        return natives.clone();
    }

    /**
     * Lists every native of the target that the facade can't serve: a native missing from the list,
     * a listed native the target no longer declares, or a signature with no same-named facade
     * method taking the same arguments. Empty means the port fits this game build.
     */
    public Set<String> mismatches(TypePool typePool) {
        Set<String> mismatches = new TreeSet<>();
        TypeDescription target = typePool.describe(className).resolve();
        TypeDescription facadeType = typePool.describe(facade.getName()).resolve();
        Set<String> listed = Set.of(natives);
        Set<String> declared = new TreeSet<>();
        for (MethodDescription.InDefinedShape method :
                target.getDeclaredMethods().filter(ElementMatchers.isNative())) {
            declared.add(method.getName());
            if (!listed.contains(method.getName())) {
                mismatches.add("unported " + signature(method));
            } else if (!facadeServes(facadeType, method)) {
                mismatches.add("changed " + signature(method));
            }
        }
        for (String name : natives) {
            if (!declared.contains(name)) {
                mismatches.add("removed " + name);
            }
        }
        return mismatches;
    }

    private static boolean facadeServes(
            TypeDescription facadeType, MethodDescription.InDefinedShape nativeMethod) {
        List<TypeDescription> wanted = nativeMethod.getParameters().asTypeList().asErasures();
        for (MethodDescription.InDefinedShape candidate :
                facadeType
                        .getDeclaredMethods()
                        .filter(ElementMatchers.named(nativeMethod.getName()))) {
            List<TypeDescription> params = candidate.getParameters().asTypeList().asErasures();

            // Facades for instance natives take the receiver as a leading @This Object.

            int skip = nativeMethod.isStatic() ? 0 : 1;
            if (params.size() == wanted.size() + skip
                    && params.subList(skip, params.size()).equals(wanted)
                    && candidate
                            .getReturnType()
                            .asErasure()
                            .equals(nativeMethod.getReturnType().asErasure())) {
                return true;
            }
        }
        return false;
    }

    private static String signature(MethodDescription method) {
        return method.getName() + method.getDescriptor();
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        for (String name : natives) {
            builder =
                    builder.method(ElementMatchers.named(name).and(ElementMatchers.isNative()))
                            .intercept(
                                    MethodDelegation.withDefaultConfiguration()
                                            .filter(ElementMatchers.named(name))
                                            .to(facade));
        }
        return builder;
    }
}
