package io.pzstorm.storm.advice.animaldatagrowwaterguard;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.asm.Advice;
import zombie.characters.animals.IsoAnimal;
import zombie.network.GameServer;

/**
 * Advice for {@code AnimalData.grow(String)} that skips the growth step while the parent animal
 * stands on a water square or next to a duplicate of its own animal ID.
 *
 * <p>Both conditions make the {@code IsoAnimal} constructor bail before {@code init(breed)}, so the
 * grown replacement comes back with {@code data == null} and vanilla's very next line, {@code
 * newAnimal.getData().setAge(...)}, NPEs. {@code checkStages()} re-runs the grow every tick, so one
 * such animal aborts {@code IsoCell.update()} forever. Vanilla already treats these two cases as
 * "delete the new animal and try again later" further down in {@code grow}; this advice applies
 * that decision before the constructor runs. The parent stays as it is and grows once it moves.
 *
 * <p>Server only. Logs at most once per animal ID per {@link #LOG_INTERVAL_MS}. No lambdas /
 * streams &mdash; advice bodies are inlined into the target method.
 */
public class AnimalDataGrowWaterGuardAdvice {

    public static final long LOG_INTERVAL_MS = 10L * 60L * 1000L;
    public static final ConcurrentHashMap<Integer, Long> LAST_LOG = new ConcurrentHashMap<>();
    public static boolean DUPLICATE_CHECK_OFF_LOGGED = false;

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.FieldValue("parent") IsoAnimal parent, @Advice.Argument(0) String newType) {
        if (!GameServer.server || parent == null) {
            return false;
        }
        boolean onWater = parent.checkForWater();
        // 42.21: checkForChickenpocalypse(IsoAnimal) replaced the no-arg world scan. The other
        // animal is not known here, so the duplicate-id defer stays off rather than guessing.
        boolean duplicate = false;
        if (!DUPLICATE_CHECK_OFF_LOGGED) {
            DUPLICATE_CHECK_OFF_LOGGED = true;
            LOGGER.error(
                    "AnimalDataGrowWaterGuardPatch: checkForChickenpocalypse now takes IsoAnimal;"
                            + " duplicate-id grow defer is off until re-verified.");
        }
        if (!onWater && !duplicate) {
            return false;
        }
        int id = parent.getAnimalID();
        long now = System.currentTimeMillis();
        Long last = LAST_LOG.get(id);
        if (last == null || now - last >= LOG_INTERVAL_MS) {
            LAST_LOG.put(id, now);
            LOGGER.warn(
                    "AnimalDataGrowWaterGuardPatch: deferring grow to {} for animalId={} type={}"
                            + " at {},{},{} reason={}",
                    newType,
                    id,
                    parent.getAnimalType(),
                    parent.getXi(),
                    parent.getYi(),
                    parent.getZi(),
                    onWater ? "water" : "duplicateId");
        }
        return true;
    }
}
