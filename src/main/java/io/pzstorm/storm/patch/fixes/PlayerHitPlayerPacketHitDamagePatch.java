package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Server only. Exit advice on {@code PlayerHitPlayerPacket.parse} that hands the parsed hits to
 * {@link PvpHitsAlwaysDamage}, which clears the failed-roll {@code ignoreDamage} flag while the
 * {@code Storm.PvpHitsAlwaysDamage} sandbox option is on.
 *
 * <p>Fail-soft: the advice suppresses its own errors, and the helper goes inert and logs when the
 * field is missing, so hits fall back to vanilla. Re-validate on game updates. The patch assumes
 * {@code PlayerHitCharacter.hits} holds the parsed {@code WeaponHit}s, and that {@code
 * WeaponHit.process} still passes {@code ignoreDamage} to {@code IsoGameCharacter.Hit}.
 */
public class PlayerHitPlayerPacketHitDamagePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.network.packets.hit.PlayerHitPlayerPacket";
    private static final String HIT = "zombie.network.fields.hit.WeaponHit";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.pvphitdamage.PlayerHitPlayerPacketParseDamageAdvice";

    public PlayerHitPlayerPacketHitDamagePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(ElementMatchers.named("parse").and(ElementMatchers.takesArguments(2)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "PlayerHitPlayerPacketHitDamagePatch: PlayerHitPlayerPacket.parse(reader,"
                            + " connection) is gone. Re-verify against the current game source.");
        }
        if (typePool.describe(HIT)
                .resolve()
                .getDeclaredFields()
                .filter(ElementMatchers.named("ignoreDamage"))
                .isEmpty()) {
            throw new IllegalStateException(
                    "PlayerHitPlayerPacketHitDamagePatch: WeaponHit.ignoreDamage is gone."
                            + " Re-verify how failed hit rolls reach the server.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(ElementMatchers.named("parse").and(ElementMatchers.takesArguments(2))));
    }
}
