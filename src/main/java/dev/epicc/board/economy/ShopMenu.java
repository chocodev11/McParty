package dev.epicc.board.economy;

import dev.epicc.config.MessageService;
import dev.epicc.party.PartyPlayer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Coin shop chest menu for one player in one round's prep phase. */
public final class ShopMenu implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int INFO_SLOT = 4;
    private static final int FIRST_ITEM_SLOT = 10;

    private final UUID partyId;
    private final int round;
    private final Inventory inventory;

    public ShopMenu(UUID partyId, int round, Component title) {
        this.partyId = partyId;
        this.round = round;
        this.inventory = Bukkit.createInventory(this, SIZE, title);
    }

    public UUID partyId() {
        return partyId;
    }

    public int round() {
        return round;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void render(MessageService messages, BoardEconomy economy, PartyPlayer player) {
        inventory.clear();
        UUID id = player.uuid();
        int purchasesLeft = economy.purchasesLeft(id, round);

        ItemStack info = new ItemStack(Material.GOLD_INGOT);
        info.editMeta(meta -> {
            meta.displayName(PrepItems.line(messages.get("shop.info-coins", MessageService.ph("coins", player.coins()))));
            meta.lore(List.of(PrepItems.line(messages.get("shop.info-purchases", MessageService.ph("count", purchasesLeft)))));
        });
        inventory.setItem(INFO_SLOT, info);

        for (ShopItem item : ShopItem.values()) {
            if (!economy.offered(item)) {
                continue;
            }
            int price = economy.price(id, item);
            int owned = economy.usable(id, item, round) + economy.locked(id, item, round);
            String hint = purchasesLeft == 0 ? "shop.lore-limit"
                    : player.coins() < price ? "shop.lore-not-enough" : "shop.lore-click-buy";
            ItemStack icon = PrepItems.icon(messages, item);
            icon.editMeta(meta -> {
                List<Component> lore = new ArrayList<>(4);
                lore.add(PrepItems.line(messages.get("shop.descriptions." + item.id())));
                lore.add(PrepItems.line(messages.get("shop.lore-price", MessageService.ph("price", price))));
                if (owned > 0) {
                    lore.add(PrepItems.line(messages.get("shop.lore-owned", MessageService.ph("count", owned))));
                }
                lore.add(PrepItems.line(messages.get(hint)));
                meta.lore(lore);
            });
            inventory.setItem(FIRST_ITEM_SLOT + item.ordinal(), icon);
        }
    }

    public Optional<ShopItem> itemAt(int slot) {
        int index = slot - FIRST_ITEM_SLOT;
        ShopItem[] items = ShopItem.values();
        return index >= 0 && index < items.length ? Optional.of(items[index]) : Optional.empty();
    }
}
