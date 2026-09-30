package io.pzstorm.storm.advice.pvphitdamage;

import io.pzstorm.storm.patch.fixes.PvpHitsAlwaysDamage;
import java.util.List;
import net.bytebuddy.asm.Advice;

/** Runs after {@code PlayerHitPlayerPacket.parse} so the parsed hits can be adjusted. */
public class PlayerHitPlayerPacketParseDamageAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.FieldValue("hits") List<?> hits) {
        PvpHitsAlwaysDamage.afterParse(hits);
    }
}
