package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.characters.IsoPlayer;
import zombie.core.ActionManager;
import zombie.core.Transaction;
import zombie.core.raknet.UdpConnection;
import zombie.network.fields.character.PlayerID;
import zombie.network.packets.GeneralActionPacket;

/**
 * Attributes a {@code GeneralActionPacket} reject to the connection that sent it. Vanilla {@code
 * processServer} hands the packet to {@code ActionManager.stop}, and {@link
 * io.pzstorm.storm.advice.actionmanager.StopAdvice} matches the queued action on the packet's
 * {@code (id, playerOnlineId)}. That {@code playerId} is client-supplied, so a client could cancel
 * another player's action by naming that player's online id.
 *
 * <p>This patch ignores the packet's {@code playerId}. It matches the byte id only against actions
 * owned by one of {@code connection.players[]} (split-screen safe) and stops that queued action
 * directly, so {@code StopAdvice} sees the stored {@code playerId} and removes only the owner's
 * entry.
 *
 * <p>The advice replaces the entire body of {@code processServer}; on exception the original
 * vanilla method runs as a fallback (matches the pattern in {@link NetTimedActionPacketPatch}).
 *
 * <p><b>Reflection:</b> the {@code state}, {@code id}, and {@code playerId} fields live in the
 * package-private {@code Action} base class; {@code ActionManager.actions} is private. Both are
 * accessed via cached reflection handles.
 */
public class GeneralActionPacketPatch extends StormClassTransformer {

    public GeneralActionPacketPatch() {
        super("zombie.network.packets.GeneralActionPacket");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(
                                                "io.pzstorm.storm.advice.generalactionpacket.ProcessServerAdvice")
                                        .resolve(),
                                locator)
                        .on(ElementMatchers.named("processServer")));
    }

    private static volatile Field stateField;
    private static volatile Field idField;
    private static volatile Field playerIdField;
    private static volatile Field actionsField;
    private static volatile Method actionManagerStopMethod;

    private static synchronized void initFieldHandles() throws ReflectiveOperationException {
        if (stateField != null) {
            return;
        }
        Class<?> actionClass = Class.forName("zombie.core.Action");

        Field sf = actionClass.getDeclaredField("state");
        sf.setAccessible(true);

        Field idf = actionClass.getDeclaredField("id");
        idf.setAccessible(true);

        Field pidf = actionClass.getDeclaredField("playerId");
        pidf.setAccessible(true);

        Field af = ActionManager.class.getDeclaredField("actions");
        af.setAccessible(true);

        Method sm = ActionManager.class.getDeclaredMethod("stop", actionClass);
        sm.setAccessible(true);

        idField = idf;
        playerIdField = pidf;
        actionsField = af;
        actionManagerStopMethod = sm;
        stateField = sf;
    }

    /**
     * Replacement for {@code GeneralActionPacket.processServer()}. On Reject, resolves the queued
     * action by matching {@code (byte id, playerId)} against the players owned by {@code
     * connection}, and stops the queued action object directly. {@code StopAdvice} then runs with a
     * properly populated {@code playerId} and removes only the cancelling player's entry.
     *
     * @return {@code true} to skip the vanilla method body.
     */
    public static boolean processServerFixed(GeneralActionPacket packet, UdpConnection connection)
            throws ReflectiveOperationException {
        if (stateField == null) {
            initFieldHandles();
        }

        Transaction.TransactionState state = (Transaction.TransactionState) stateField.get(packet);
        if (state != Transaction.TransactionState.Reject) {
            return true;
        }

        byte targetId = idField.getByte(packet);

        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<Object> queue =
                (ConcurrentLinkedQueue<Object>) actionsField.get(null);

        Object queued = null;
        for (Object a : queue) {
            if (idField.getByte(a) != targetId) {
                continue;
            }
            PlayerID aPid = (PlayerID) playerIdField.get(a);
            if (isOwnedByConnection(aPid, connection)) {
                queued = a;
                break;
            }
        }

        if (queued == null) {
            LOGGER.debug(
                    "GeneralActionPacket reject id={} from connection={}: no matching queued action",
                    targetId,
                    connection.getSteamId());
            return true;
        }

        LOGGER.debug(
                "GeneralActionPacket reject id={} resolved to queued action; delegating to ActionManager.stop",
                targetId);
        actionManagerStopMethod.invoke(null, queued);
        return true;
    }

    private static boolean isOwnedByConnection(PlayerID pid, UdpConnection connection) {
        short onlineId = pid.getID();
        for (IsoPlayer p : connection.players) {
            if (p != null && p.getOnlineID() == onlineId) {
                return true;
            }
        }
        return false;
    }
}
