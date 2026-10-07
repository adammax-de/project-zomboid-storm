package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;
import lombok.Getter;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;

/**
 * Server only. A character has been registered on a connection: {@code
 * GameServer.receivePlayerConnect} has put it in {@code IDToPlayerMap}, marked the connection fully
 * connected and told every client about it. Fires on the first join and again on every respawn (the
 * client re-sends PlayerConnect for the new character) and for each split-screen coop player. Main
 * thread.
 *
 * <p>Fired by {@code GameServerPlayerConnectionEventsPatch}.
 */
public class OnPlayerEnterWorldEvent implements ZomboidEvent {

    /** The character that just entered the world. */
    @Getter private final IsoPlayer player;

    /** The connection that owns it. */
    @Getter private final UdpConnection connection;

    /** Account name of the player, null only when the player is unknown. */
    @Getter private final String username;

    public OnPlayerEnterWorldEvent(IsoPlayer player, UdpConnection connection) {
        this.player = player;
        this.connection = connection;
        this.username = player != null ? player.getUsername() : null;
    }

    @Override
    public String getName() {
        return "OnPlayerEnterWorld";
    }
}
