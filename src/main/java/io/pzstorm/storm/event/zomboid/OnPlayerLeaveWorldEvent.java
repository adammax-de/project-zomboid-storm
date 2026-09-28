package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;
import lombok.Getter;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;

/**
 * Server only. A character is about to be removed from the world: fires at the top of {@code
 * GameServer.disconnectPlayer}, so the player is still in {@code IDToPlayerMap} and still owns its
 * connection slot while handlers run. Main thread.
 *
 * <p>{@code disconnectPlayer} runs when a connection drops and also when a client swaps the
 * character on a live connection (respawn after death, a coop player leaving). {@link
 * #isConnectionClosed()} tells the two apart: {@code true} means the connection itself is going
 * away; {@code false} means the same connection will shortly register a new character and fire
 * {@link OnPlayerEnterWorldEvent}.
 *
 * <p>Fired by {@code GameServerPlayerConnectionEventsPatch}.
 */
public class OnPlayerLeaveWorldEvent implements ZomboidEvent {

    /** The character leaving the world. */
    @Getter private final IsoPlayer player;

    /** The connection that owned it. */
    @Getter private final UdpConnection connection;

    /** True when the whole connection is being torn down, false for a character swap. */
    @Getter private final boolean connectionClosed;

    /** Account name of the player, null only when the player is unknown. */
    @Getter private final String username;

    public OnPlayerLeaveWorldEvent(
            IsoPlayer player, UdpConnection connection, boolean connectionClosed) {
        this.player = player;
        this.connection = connection;
        this.connectionClosed = connectionClosed;
        this.username = player != null ? player.getUsername() : null;
    }

    @Override
    public String getName() {
        return "OnPlayerLeaveWorld";
    }
}
