package dev.epicc.resourcepack;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

public final class ResourcePackListener implements Listener {

    private final ResourcePackService packs;

    public ResourcePackListener(ResourcePackService packs) {
        this.packs = packs;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!packs.isReady() || !packs.sendOnJoin()) {
            return;
        }
        packs.offerLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (!packs.isReady() || !event.getID().equals(packs.packId())) {
            return;
        }
        Player player = event.getPlayer();
        switch (event.getStatus()) {
            case DECLINED -> {
                packs.notifyDeclined(player);
                if (packs.kickOnDecline()) {
                    player.kick(net.kyori.adventure.text.Component.text(packs.kickMessage()));
                }
            }
            case FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED ->
                    packs.notifyFailed(player, event.getStatus().name());
            default -> {
                // ACCEPTED, DOWNLOADED, SUCCESSFULLY_LOADED — no chat spam
            }
        }
    }
}
