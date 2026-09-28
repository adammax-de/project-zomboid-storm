package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;

/**
 * Typed event dispatched when a large-area zip request packet is processed on the server.
 *
 * <p>Build 42.21 removed {@code zombie.network.packets.RequestLargeAreaZipPacket}. The event stays
 * so existing listeners still resolve; {@link #getPacket()} returns the raw packet object.
 */
public class RequestLargeAreaZipPacketEvent extends PacketEvent {

    public RequestLargeAreaZipPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public Object getPacket() {
        return getRawPacket();
    }

    @Override
    public String getName() {
        return "RequestLargeAreaZipPacketEvent";
    }
}
