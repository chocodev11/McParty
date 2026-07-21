package dev.epicc.seamless;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.SimplePacketListenerAbstract;
import com.github.retrooper.packetevents.event.simple.PacketPlaySendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;

import java.util.UUID;

/**
 * Loaded only when PacketEvents is present (see {@link SeamlessWorldChangeService}).
 */
final class SeamlessRespawnListener extends SimplePacketListenerAbstract {

    private final SeamlessWorldChangeService service;

    private SeamlessRespawnListener(SeamlessWorldChangeService service) {
        super(PacketListenerPriority.HIGH);
        this.service = service;
    }

    static void register(SeamlessWorldChangeService service) {
        PacketEvents.getAPI().getEventManager().registerListener(new SeamlessRespawnListener(service));
    }

    @Override
    public void onPacketPlaySend(PacketPlaySendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.RESPAWN) {
            return;
        }
        UUID id = event.getUser().getUUID();
        if (service.consumeMark(id)) {
            event.setCancelled(true);
        }
    }
}
