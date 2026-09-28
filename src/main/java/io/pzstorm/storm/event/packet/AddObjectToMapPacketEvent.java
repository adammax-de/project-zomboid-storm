package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.AddObjectToMapPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.AddObjectToMapPacket} is processed on
 * the server.
 */
public class AddObjectToMapPacketEvent extends PacketEvent {

    public AddObjectToMapPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AddObjectToMapPacket getPacket() {
        return (AddObjectToMapPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AddObjectToMapPacketEvent";
    }
}
