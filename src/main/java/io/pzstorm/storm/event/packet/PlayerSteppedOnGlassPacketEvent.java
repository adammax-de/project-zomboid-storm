package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.PlayerSteppedOnGlassPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.PlayerSteppedOnGlassPacket}
 * is processed on the server.
 */
public class PlayerSteppedOnGlassPacketEvent extends PacketEvent {

    public PlayerSteppedOnGlassPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public PlayerSteppedOnGlassPacket getPacket() {
        return (PlayerSteppedOnGlassPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "PlayerSteppedOnGlassPacketEvent";
    }
}
