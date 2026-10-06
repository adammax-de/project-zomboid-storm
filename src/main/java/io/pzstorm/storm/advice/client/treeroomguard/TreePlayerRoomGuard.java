package io.pzstorm.storm.advice.client.treeroomguard;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.concurrent.atomic.AtomicLong;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;

/**
 * Tells {@link IsoTreePlayerRoomGuardAdvice} whether the player's square lacks the {@code IsoRoom}
 * that vanilla {@code IsoTree.isPlayerInsideARoom} dereferences unguarded.
 */
public class TreePlayerRoomGuard {

    /** Calls where the player counted as inside a room but the square had no {@code IsoRoom}. */
    public static final AtomicLong GUARDED = new AtomicLong();

    /** Checks that threw; vanilla then runs unguarded for that call. */
    public static final AtomicLong FAILED = new AtomicLong();

    /** Returns {@code true} when the vanilla body must be skipped. */
    public static boolean hasNoRoom(Object playerObj) {
        try {
            IsoGridSquare square = ((IsoPlayer) playerObj).getSquare();
            if (square != null && square.getRoom() != null) {
                return false;
            }
            if (square != null && square.isInARoom() && GUARDED.incrementAndGet() == 1) {
                LOGGER.warn(
                        "IsoTreePlayerRoomNullGuardPatch: player stands in a player-built room at"
                                + " {},{},{} that has no IsoRoom; trees stay opaque there instead"
                                + " of throwing every frame",
                        square.getX(),
                        square.getY(),
                        square.getZ());
            }
            return true;
        } catch (Throwable t) {
            if (FAILED.incrementAndGet() == 1) {
                LOGGER.error("IsoTreePlayerRoomNullGuardPatch: room check failed", t);
            }
            return false;
        }
    }
}
