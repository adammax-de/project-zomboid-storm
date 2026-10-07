package io.pzstorm.storm.patch.lua;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnLuaManagerInitEvent;
import io.pzstorm.storm.lua.LuaFileHooks;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.ZomboidFileSystem;

/**
 * Patches {@link zombie.Lua.LuaManager} to log Lua file loads via RunLuaInternal and to run {@link
 * LuaFileHooks} after the files they follow.
 */
public class LuaManagerPatch extends StormClassTransformer {

    public LuaManagerPatch() {
        super("zombie.Lua.LuaManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(RunLuaInternalAdvice.class)
                                .on(
                                        ElementMatchers.named("RunLuaInternal")
                                                .and(
                                                        ElementMatchers.takesArgument(
                                                                0, String.class))))
                .visit(Advice.to(InitAdvice.class).on(ElementMatchers.named("init")));
    }

    public static class RunLuaInternalAdvice {
        @Advice.OnMethodEnter
        public static String onRunLuaInternal(@Advice.Argument(0) String filename) {
            if (filename != null) {
                String absolutePath = ZomboidFileSystem.instance.resolveFileOrGUID(filename);

                LOGGER.debug("[RunLuaInternal] Loading file: {}", absolutePath);
            }
            return LuaFileHooks.pending(filename);
        }

        @Advice.OnMethodExit
        public static void afterRunLuaInternal(@Advice.Enter String hook) {
            LuaFileHooks.run(hook);
        }
    }

    public static class InitAdvice {
        @Advice.OnMethodExit
        public static void afterInit() {
            LOGGER.debug("LuaManager.init()");
            StormEventDispatcher.dispatchEvent(new OnLuaManagerInitEvent());
        }
    }
}
