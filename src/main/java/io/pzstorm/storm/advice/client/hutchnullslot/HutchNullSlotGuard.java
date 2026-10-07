package io.pzstorm.storm.advice.client.hutchnullslot;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

/**
 * Removes the null animal slots that vanilla client code leaves in {@code IsoHutch.animalInside},
 * invoked from {@link IsoHutchNullSlotAdvice} before {@code removeFromWorld()} or {@code
 * releaseAllAnimals()} iterates them.
 */
public class HutchNullSlotGuard {

    /** Null slots removed across all coops. */
    public static final AtomicLong STRIPPED = new AtomicLong();

    /** Strips that threw; vanilla then runs on the unmodified map. */
    public static final AtomicLong FAILED = new AtomicLong();

    public static void stripNullSlots(Object hutchObj, Map<?, ?> animalInside) {
        try {
            int removed = 0;
            for (Iterator<?> it = animalInside.values().iterator(); it.hasNext(); ) {
                if (it.next() == null) {
                    it.remove();
                    removed++;
                }
            }
            if (STRIPPED.getAndAdd(removed) == 0) {
                IsoGridSquare square = ((IsoObject) hutchObj).getSquare();
                LOGGER.warn(
                        "IsoHutchNullAnimalSlotGuardPatch: removed {} null animal slot(s) from the"
                                + " coop at {} before the vanilla loop dereferenced them",
                        removed,
                        square == null
                                ? "an unknown square"
                                : square.getX() + "," + square.getY() + "," + square.getZ());
            }
        } catch (Throwable t) {
            if (FAILED.incrementAndGet() == 1) {
                LOGGER.error("IsoHutchNullAnimalSlotGuardPatch: failed to strip null slots", t);
            }
        }
    }
}
