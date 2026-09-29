package io.pzstorm.storm.lua;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.util.LuaPatchUtils;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import zombie.Lua.LuaManager;

/**
 * Runs a Storm resource Lua file immediately after a specific game Lua file loads, so Storm can
 * patch a vanilla function before any mod file captures it. Hooks live under {@code lua/filehooks/}
 * and are keyed by the path suffix of the file they follow.
 */
public final class LuaFileHooks {

    private static final Map<String, String> HOOKS =
            Map.of(
                    "lua/shared/Moveables/ISMoveableSpriteProps.lua",
                    "/lua/filehooks/shared/Moveables/ISMoveableSpriteProps.lua");

    private LuaFileHooks() {}

    /**
     * Returns the hook to run after {@code filename}, or null. Files LuaManager has already loaded
     * return null because RunLuaInternal hands back the cached result without re-running them.
     */
    public static String pending(String filename) {
        try {
            if (filename == null) {
                return null;
            }
            String normalized = filename.replace('\\', '/');
            for (Map.Entry<String, String> hook : HOOKS.entrySet()) {
                if (normalized.endsWith(hook.getKey())) {
                    return LuaManager.loaded.contains(normalized) ? null : hook.getValue();
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Lua file hook lookup failed for {}", filename, t);
        }
        return null;
    }

    public static void run(String resourcePath) {
        if (resourcePath == null) {
            return;
        }
        try (InputStream is = LuaFileHooks.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOGGER.error("Lua file hook missing from storm.jar: {}", resourcePath);
                return;
            }
            LuaPatchUtils.injectLuaCode(
                    new String(is.readAllBytes(), StandardCharsets.UTF_8), resourcePath);
            LOGGER.info("Ran Lua file hook {}", resourcePath);
        } catch (Throwable t) {
            LOGGER.error("Lua file hook failed: {}", resourcePath, t);
        }
    }
}
