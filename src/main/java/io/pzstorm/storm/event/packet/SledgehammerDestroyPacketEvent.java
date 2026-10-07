package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.SledgehammerDestroyPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SledgehammerDestroyPacket} is processed
 * on the server.
 */
public class SledgehammerDestroyPacketEvent extends PacketEvent {

    public SledgehammerDestroyPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SledgehammerDestroyPacket getPacket() {
        return (SledgehammerDestroyPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SledgehammerDestroyPacketEvent";
    }
}
