package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.BuildActionPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.BuildActionPacket} is processed on the
 * server.
 */
public class BuildActionPacketEvent extends PacketEvent {

    public BuildActionPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public BuildActionPacket getPacket() {
        return (BuildActionPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "BuildActionPacketEvent";
    }
}
