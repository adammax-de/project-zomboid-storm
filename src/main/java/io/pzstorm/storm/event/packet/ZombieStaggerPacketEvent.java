package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.ZombieStaggerPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.ZombieStaggerPacket} is
 * processed on the server.
 */
public class ZombieStaggerPacketEvent extends PacketEvent {

    public ZombieStaggerPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ZombieStaggerPacket getPacket() {
        return (ZombieStaggerPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ZombieStaggerPacketEvent";
    }
}
