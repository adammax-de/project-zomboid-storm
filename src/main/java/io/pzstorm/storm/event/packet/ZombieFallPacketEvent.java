package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.ZombieFallPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.ZombieFallPacket} is
 * processed on the server.
 */
public class ZombieFallPacketEvent extends PacketEvent {

    public ZombieFallPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ZombieFallPacket getPacket() {
        return (ZombieFallPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ZombieFallPacketEvent";
    }
}
