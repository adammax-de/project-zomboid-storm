package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.iso.areas.SafeHouse;
import zombie.network.packets.safehouse.SafehouseChangeRespawnPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.safehouse.SafehouseChangeRespawnPacket}
 * is processed on the server.
 */
public class SafehouseChangeRespawnPacketEvent extends PacketEvent {

    private boolean wasRespawning;

    public SafehouseChangeRespawnPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafehouseChangeRespawnPacket getPacket() {
        return (SafehouseChangeRespawnPacket) getRawPacket();
    }

    @Override
    public void capturePreState() {
        SafeHouse safehouse = getPacket().getSafehouse();
        if (safehouse != null) {
            wasRespawning = safehouse.isRespawnInSafehouse(getPacket().getUsername());
        }
    }

    public boolean wasRespawning() {
        return wasRespawning;
    }

    @Override
    public String getName() {
        return "SafehouseChangeRespawnPacketEvent";
    }
}
