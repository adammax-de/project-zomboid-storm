package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.NetTimedActionPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.NetTimedActionPacket} is processed on
 * the server.
 */
public class NetTimedActionPacketEvent extends PacketEvent {

    public NetTimedActionPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public NetTimedActionPacket getPacket() {
        return (NetTimedActionPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "NetTimedActionPacketEvent";
    }
}
