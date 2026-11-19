package dev.epicc.board.economy;

import dev.epicc.party.PartyManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

/** Routes prep-phase hotbar and shop menu input to the party; keeps prep items from leaving the hotbar. */
public final class PrepListener implements Listener {

    private final JavaPlugin plugin;
    private final PartyManager parties;

    public PrepListener(JavaPlugin plugin, PartyManager parties) {
        this.plugin = plugin;
        this.parties = parties;
    }

    // Air clicks arrive already cancelled, so cancelled events are not ignored here
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() == Action.PHYSICAL) {
            return;
        }
        Optional<String> action = PrepItems.action(plugin, event.getItem());
        if (action.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (event.getAction().isRightClick()) {
            Player player = event.getPlayer();
            parties.prepAction(player, action.get()).ifPresent(player::sendMessage);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        Optional<String> action = PrepItems.action(plugin, player.getInventory().getItemInMainHand());
        if (action.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        parties.prepAction(player, action.get()).ifPresent(player::sendMessage);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder(false) instanceof ShopMenu menu) {
            event.setCancelled(true);
            if (top.equals(event.getClickedInventory()) && (event.isLeftClick() || event.isRightClick())) {
                parties.shopClick(player, menu, event.getSlot());
            }
            return;
        }
        if (tagged(event.getCurrentItem()) || tagged(event.getCursor())
                || event.getClick() == ClickType.NUMBER_KEY
                && tagged(player.getInventory().getItem(event.getHotbarButton()))
                || event.getClick() == ClickType.SWAP_OFFHAND
                && tagged(player.getInventory().getItemInOffHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ShopMenu || tagged(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (tagged(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (tagged(event.getMainHandItem()) || tagged(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::tagged);
    }

    // The party leave runs a tick after quitting, once the player file is already saved
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        PrepItems.strip(plugin, event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        PrepItems.strip(plugin, event.getPlayer());
    }

    private boolean tagged(ItemStack stack) {
        return PrepItems.isTagged(plugin, stack);
    }
}
