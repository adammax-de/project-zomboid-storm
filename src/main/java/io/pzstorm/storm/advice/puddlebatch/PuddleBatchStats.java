package io.pzstorm.storm.advice.puddlebatch;

/**
 * Counters for the puddle batch cache. The game thread writes the pass counters once per pass and
 * the render thread writes the draw counters once per command, so each field has one writer. Read
 * them through eval with {@link PuddleBatchRenderer#stats()}.
 */
final class PuddleBatchStats {

    static volatile long passes;
    static volatile long vanillaPasses;
    static volatile long built;
    static volatile long reused;
    static volatile long rebuildInvalid;
    static volatile long rebuildList;
    static volatile long rebuildMask;
    static volatile long rebuildExpired;
    static volatile long buildMismatches;
    static volatile long overCap;
    static volatile long lightChecks;
    static volatile long lightPatches;
    static volatile long depthShifts;
    static volatile long queuedRebuild;
    static volatile long queuedLight;
    static volatile long queuedShift;
    static volatile long queuedRetry;
    static volatile long settingsInvalidations;
    static volatile long worldDrops;
    static volatile long swept;

    static volatile long commands;
    static volatile long uploads;
    static volatile long draws;
    static volatile long staleSkips;
    static volatile long buffersCreated;
    static volatile long buffersDeleted;

    private PuddleBatchStats() {}

    static String describe() {
        return "passes="
                + passes
                + " vanillaPasses="
                + vanillaPasses
                + " built="
                + built
                + " reused="
                + reused
                + " rebuilds(invalid="
                + rebuildInvalid
                + " list="
                + rebuildList
                + " mask="
                + rebuildMask
                + " expired="
                + rebuildExpired
                + ") buildMismatches="
                + buildMismatches
                + " overCap="
                + overCap
                + " lightChecks="
                + lightChecks
                + " lightPatches="
                + lightPatches
                + " depthShifts="
                + depthShifts
                + " uploadsQueued(rebuild="
                + queuedRebuild
                + " light="
                + queuedLight
                + " shift="
                + queuedShift
                + " retry="
                + queuedRetry
                + ") settingsInvalidations="
                + settingsInvalidations
                + " worldDrops="
                + worldDrops
                + " swept="
                + swept
                + " commands="
                + commands
                + " uploads="
                + uploads
                + " draws="
                + draws
                + " staleSkips="
                + staleSkips
                + " buffersCreated="
                + buffersCreated
                + " buffersDeleted="
                + buffersDeleted;
    }
}
