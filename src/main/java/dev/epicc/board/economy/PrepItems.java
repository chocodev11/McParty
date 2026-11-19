package dev.epicc.board.economy;

import dev.epicc.board.dice.DiceItems;
import dev.epicc.config.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Prep-phase hotbar items. They only mirror {@link BoardEconomy} state, so every tagged stack is
 * thrown away when the phase ends; nothing is ever read back from the inventory but the action.
 */
public final class PrepItems {

    private static final String KEY_ACTION = "board_prep_action";
    static final String READY = "ready";
    static final String SHOP = "shop";
    static final String USE_PREFIX = "use:";

    private PrepItems() {
    }

    public static ItemStack ready(Plugin plugin, MessageService messages, boolean done) {
        ItemStack stack = new ItemStack(done ? Material.GRAY_DYE : Material.LIME_DYE);
        stack.editMeta(meta -> meta.displayName(line(messages.get(done ? "shop.item-ready-done" : "shop.item-ready"))));
        return tag(plugin, stack, READY);
    }

    public static ItemStack shop(Plugin plugin, MessageService messages) {
        ItemStack stack = new ItemStack(Material.EMERALD);
        stack.editMeta(meta -> meta.displayName(line(messages.get("shop.item-shop"))));
        return tag(plugin, stack, SHOP);
    }

    /** Held units of {@code item}; right-click uses one. */
    public static ItemStack held(Plugin plugin, MessageService messages, ShopItem item, int usable, int locked) {
        ItemStack stack = icon(messages, item);
        stack.setAmount(Math.clamp(usable + locked, 1, stack.getMaxStackSize()));
        stack.editMeta(meta -> {
            List<Component> lore = new ArrayList<>(4);
            lore.add(line(messages.get("shop.descriptions." + item.id())));
            lore.add(line(messages.get("shop.lore-owned", MessageService.ph("count", usable))));
            if (locked > 0) {
                lore.add(line(messages.get("shop.lore-locked", MessageService.ph("count", locked))));
            }
            if (usable > 0) {
                lore.add(line(messages.get("shop.lore-click-use")));
            }
            meta.lore(lore);
        });
        return tag(plugin, stack, USE_PREFIX + item.id());
    }

    /** Untagged stack with the item's look and name, shared by the hotbar and the shop menu. */
    public static ItemStack icon(MessageService messages, ShopItem item) {
        ItemStack stack = switch (item) {
            case DOUBLE_COINS -> new ItemStack(Material.GOLD_NUGGET);
            case WARP_PIPE -> new ItemStack(Material.ENDER_PEARL);
            default -> item.die()
                    .map(skin -> DiceItems.face(skin, skin.maxFace()))
                    .orElseGet(() -> new ItemStack(Material.PAPER));
        };
        stack.editMeta(meta -> meta.displayName(line(messages.get("shop.names." + item.id()))));
        return stack;
    }

    public static Optional<String> action(Plugin plugin, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(stack.getPersistentDataContainer().get(key(plugin), PersistentDataType.STRING));
    }

    public static boolean isTagged(Plugin plugin, ItemStack stack) {
        return action(plugin, stack).isPresent();
    }

    /** Remove every prep item from storage, armour, off-hand and the cursor. */
    public static void strip(Plugin plugin, Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isTagged(plugin, contents[i])) {
                inventory.setItem(i, null);
            }
        }
        if (isTagged(plugin, player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
    }

    static Component line(Component text) {
        return text.decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack tag(Plugin plugin, ItemStack stack, String action) {
        stack.editPersistentDataContainer(pdc -> pdc.set(key(plugin), PersistentDataType.STRING, action));
        return stack;
    }

    private static NamespacedKey key(Plugin plugin) {
        return new NamespacedKey(plugin, KEY_ACTION);
    }
}
